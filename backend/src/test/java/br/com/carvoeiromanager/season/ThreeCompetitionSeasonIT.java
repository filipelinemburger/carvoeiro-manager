package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole slice, end to end, on the real seed: a career now ships three competitions — Campeonato
 * Catarinense and Série B, which the managed club (Criciúma) plays, and Série A, which it does not.
 * One season is driven from creation to FINISHED and both halves of the promise are checked:
 *
 * <ul>
 *   <li>Série A really played out — all 380 fixtures, a full 20-club table, everyone on 38 games;</li>
 *   <li>and it cost the player nothing — their last matchday is still on the same day of the same
 *       270-day window it fell on before background competitions existed.</li>
 * </ul>
 *
 * <p>Distinct from {@link BackgroundCompetitionIT}, which builds a synthetic background league to
 * probe the advance's mechanics round by round. This one asserts nothing about mechanics; it takes
 * the shipped seed exactly as a player gets it and checks the season's final state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
// Runs on the FULL seed, Série A included: the whole point of this test is the three-competition season.
// The test profile otherwise trims the seed to the player's own competitions to keep
// the suite fast — see carvoeiro.seed.competitions in application-test.yml.
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class ThreeCompetitionSeasonIT {

    private static final String CATARINENSE = "Campeonato Catarinense";
    private static final String SERIE_B = "Série B";
    private static final String SERIE_A = "Série A";

    private static final int SERIE_A_CLUBS = 20;
    private static final int SERIE_A_ROUNDS = 2 * (SERIE_A_CLUBS - 1);
    private static final int SERIE_A_FIXTURES = SERIE_A_ROUNDS * (SERIE_A_CLUBS / 2);

    /**
     * The managed season's width in days, unchanged by this slice: 55 interleaved match-units
     * (Catarinense's 11 + 2 + 2 + 2 phase rounds, Série B's 38) at
     * {@link SeasonCalendarGenerator#MATCH_INTERVAL_DAYS}-day spacing, so the finale sits
     * 54 × 5 = 270 days after the 15 January opening. Série A adds 38 more rounds and must not
     * move it.
     */
    private static final int MANAGED_SEASON_DAYS = 270;

    /** A whole season's advances (managed rounds + transfer stops) with room to spare. */
    private static final int ADVANCE_GUARD = 500;

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired BackgroundCompetitionService backgroundCompetitionService;
    @Autowired StandingsService standingsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    @Test
    void aFullSeasonRunsThreeCompetitionsWithoutLengtheningThePlayersYear() {
        User user = User.create("three_comp_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse career = saveGameService.createCareer(user.getId());
        UUID saveGameId = career.id();
        UUID managedClubId = saveGameRepository.findById(saveGameId).orElseThrow().getManagedClubId();

        // The seed ships all three; no fixture in this test builds one. Two are the player's, one
        // is not — membership is the only thing that decides which.
        Map<String, Competition> byName = competitionRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Competition::getName, Function.identity()));
        assertThat(byName).containsOnlyKeys(CATARINENSE, SERIE_B, SERIE_A);
        UUID serieAId = byName.get(SERIE_A).getId();
        assertThat(backgroundCompetitionService.isManaged(byName.get(CATARINENSE).getId(), managedClubId))
                .isTrue();
        assertThat(backgroundCompetitionService.isManaged(byName.get(SERIE_B).getId(), managedClubId))
                .isTrue();
        assertThat(backgroundCompetitionService.isManaged(serieAId, managedClubId)).isFalse();
        assertThat(competitionClubRepository.findByCompetitionId(serieAId)).hasSize(SERIE_A_CLUBS);

        // --- The player's year is untouched ------------------------------------------------
        LocalDate seasonStart = LocalDate.of(career.seasonYear(), 1, 15);
        LocalDate lastManagedDate = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), managedClubId))
                .map(SeasonEvent::getEventDate)
                .max(LocalDate::compareTo)
                .orElseThrow();

        assertThat(lastManagedDate).isEqualTo(seasonStart.plusDays(MANAGED_SEASON_DAYS));
        // ...which is still inside the season's own calendar year — 270 days from 15 January.
        assertThat(lastManagedDate).isBefore(LocalDate.of(career.seasonYear() + 1, 1, 1));

        // --- Drive the whole season --------------------------------------------------------
        int advances = 0;
        while (saveGameRepository.findById(saveGameId).orElseThrow().getStatus()
                != SaveGameStatus.FINISHED && advances++ < ADVANCE_GUARD) {
            seasonAdvanceService.advance(saveGameId, user.getId());
        }
        assertThat(advances).isLessThan(ADVANCE_GUARD);

        SaveGame finished = saveGameRepository.findById(saveGameId).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(SaveGameStatus.FINISHED);
        // The season ended on the player's last match, not on Série A's — the 380 background
        // fixtures never pushed the clock past the finale.
        assertThat(finished.getCurrentDate()).isEqualTo(lastManagedDate);

        // --- Série A played out in the background ------------------------------------------
        List<Fixture> serieAFixtures =
                fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, serieAId);
        assertThat(serieAFixtures).hasSize(SERIE_A_FIXTURES).allMatch(Fixture::isPlayed);
        assertThat(serieAFixtures).allMatch(f -> !f.getMatchDate().isAfter(lastManagedDate));

        List<StandingRow> table = standingsService.computeStandings(finished, serieAId, null, null);
        assertThat(table).hasSize(SERIE_A_CLUBS);
        assertThat(table).allSatisfy(r -> assertThat(r.played()).isEqualTo(SERIE_A_ROUNDS));

        // Zero-sum: every goal scored in the competition was conceded in it.
        assertThat(table.stream().mapToInt(StandingRow::goalsFor).sum())
                .isEqualTo(table.stream().mapToInt(StandingRow::goalsAgainst).sum());
        // ...and it is genuinely a played-out table, not 20 goalless rows.
        assertThat(table.stream().mapToInt(StandingRow::goalsFor).sum()).isPositive();

        // The player's club never appears in it — the whole reason Série A is background.
        assertThat(table).noneMatch(r -> r.clubId().equals(managedClubId));

        // --- And the player's own league finished too ---------------------------------------
        // Série B is the same shape as Série A but the player is in it: both 20-club double
        // round-robins must end complete, which is what makes "three competitions ran" true
        // rather than "one ran and one was skipped".
        UUID serieBId = byName.get(SERIE_B).getId();
        assertThat(fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, serieBId))
                .hasSize(SERIE_A_FIXTURES)
                .allMatch(Fixture::isPlayed);
        assertThat(standingsService.computeStandings(finished, serieBId, null, null))
                .hasSize(SERIE_A_CLUBS)
                .allSatisfy(r -> assertThat(r.played()).isEqualTo(SERIE_A_ROUNDS));

        // Nothing was left on the queue in any competition, managed or background.
        assertThat(seasonEventRepository.findBySaveGameId(saveGameId))
                .isNotEmpty()
                .allMatch(SeasonEvent::isProcessed);
    }
}
