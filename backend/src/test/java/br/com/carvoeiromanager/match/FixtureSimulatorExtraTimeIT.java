package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural coverage for the knockout-tie extra-time trigger in {@link FixtureSimulator}
 * (the instant engine's path). Fixture ids are random per test run ({@code Fixture.create} calls
 * {@code UUID.randomUUID()}), so the match seed — and therefore any given fixture's natural
 * score — is NOT stable across runs; these tests assert BEHAVIOUR (does EXTRA_TIME_START appear
 * or not) rather than literal scorelines. Byte-identical-score determinism for league matches is
 * covered separately, at the {@code MatchSimulationService} level, by {@link ExtraTimeTest}
 * (fixed {@code TeamMatchInput} + fixed seed, no DB, no UUID randomness).
 *
 * <p>Each "second leg" scenario first PEEKS the leg's natural 90-minute score via
 * {@link FixtureSimulator#simulatePureWithXi} (documented as performing no writes), then builds
 * a first leg whose score forces the desired aggregate outcome, then runs the real simulation.
 * Reusing the SAME leg-2 {@link Fixture} instance for the peek and the real call keeps the seed
 * (and therefore the natural score) identical between the two.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureSimulatorExtraTimeIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchEventRepository matchEventRepository;

    private UUID saveGameId;
    private UUID managedClubId;
    private UUID competitionId;
    private SaveGame save;
    private List<UUID> aiClubIds;

    @BeforeEach
    void setUp() {
        User user = User.create("extratime_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        saveGameId = response.id();
        managedClubId = response.managedClubId();
        save = saveGameRepository.findById(saveGameId).orElseThrow();

        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        competitionId = competition.getId();

        List<Fixture> aiFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId)
                .stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .toList();
        assertThat(aiFixtures).isNotEmpty();
        aiClubIds = List.of(aiFixtures.get(0).getHomeClubId(), aiFixtures.get(0).getAwayClubId());
    }

    @Test
    void aLevelSecondLegGoesToExtraTime() {
        UUID clubA = aiClubIds.get(0);
        UUID clubB = aiClubIds.get(1);
        LocalDate matchDate = LocalDate.now();

        // Leg 2: clubB hosts (clubA was leg 1's home side, so it's away here).
        Fixture leg2 = Fixture.create(saveGameId, competitionId, 901, clubB, clubA, matchDate);
        PrecomputedFixture peek = fixtureSimulator.simulatePureWithXi(leg2, saveGameId, managedClubId);
        int homeGoals2 = peek.match().homeGoals();
        int awayGoals2 = peek.match().awayGoals();

        // Leg 1's score set EQUAL to leg 2's own (homeGoals2, awayGoals2) makes the crossover
        // aggregate trivially level: aggHome = homeGoals2 + leg1Away(=awayGoals2), aggAway =
        // awayGoals2 + leg1Home(=homeGoals2) — both homeGoals2 + awayGoals2, always equal.
        Fixture leg1 = Fixture.create(saveGameId, competitionId, 901, clubA, clubB, matchDate);
        leg1.recordResult(homeGoals2, awayGoals2);
        UUID tieId = UUID.randomUUID();
        leg1.setTieId(tieId);
        leg1.setLeg(1);
        fixtureRepository.save(leg1);

        leg2.setTieId(tieId);
        leg2.setLeg(2);

        fixtureSimulator.simulateRound(save, competitionId, List.of(leg2));

        // The persisted score is the FULL 1-120 result, not the peeked 90-minute score — extra
        // time can only ADD goals (minutes 1-90 replay identically off the same seed; see
        // ExtraTimeTest.extendingToExtraTimeReproducesTheIdenticalFirst90Minutes for that proof
        // in isolation), so the final score is always >= what was peeked.
        assertThat(leg2.getHomeGoals()).isGreaterThanOrEqualTo(homeGoals2);
        assertThat(leg2.getAwayGoals()).isGreaterThanOrEqualTo(awayGoals2);
        List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(leg2.getId()));
        assertThat(events).as("a level second leg must record an EXTRA_TIME_START marker")
                .anyMatch(e -> EventType.EXTRA_TIME_START.name().equals(e.getType())
                        && e.getMinute() == MatchMath.MINUTES);
        assertThat(events).allSatisfy(e -> assertThat(e.getMinute()).isBetween(1, MatchMath.EXTRA_TIME_END));
    }

    @Test
    void aDecidedSecondLegDoesNotGoToExtraTime() {
        UUID clubA = aiClubIds.get(0);
        UUID clubB = aiClubIds.get(1);
        LocalDate matchDate = LocalDate.now();

        Fixture leg2 = Fixture.create(saveGameId, competitionId, 902, clubB, clubA, matchDate);
        PrecomputedFixture peek = fixtureSimulator.simulatePureWithXi(leg2, saveGameId, managedClubId);
        int homeGoals2 = peek.match().homeGoals();
        int awayGoals2 = peek.match().awayGoals();

        // leg1Away - leg1Home = 0 - 1000 = -1000, which can never equal the (small, realistic)
        // awayGoals2 - homeGoals2 needed for a level aggregate — the tie is decisively NOT level
        // regardless of what leg2's natural score turns out to be.
        Fixture leg1 = Fixture.create(saveGameId, competitionId, 902, clubA, clubB, matchDate);
        leg1.recordResult(1000, 0);
        UUID tieId = UUID.randomUUID();
        leg1.setTieId(tieId);
        leg1.setLeg(1);
        fixtureRepository.save(leg1);

        leg2.setTieId(tieId);
        leg2.setLeg(2);

        fixtureSimulator.simulateRound(save, competitionId, List.of(leg2));

        assertThat(leg2.getHomeGoals()).isEqualTo(homeGoals2);
        assertThat(leg2.getAwayGoals()).isEqualTo(awayGoals2);
        List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(leg2.getId()));
        assertThat(events).as("a decided second leg must not record an EXTRA_TIME_START marker")
                .noneMatch(e -> EventType.EXTRA_TIME_START.name().equals(e.getType()));
        assertThat(events).allSatisfy(e -> assertThat(e.getMinute()).isBetween(1, MatchMath.MINUTES));
    }

    @Test
    void aFirstLegNeverGoesToExtraTime() {
        UUID clubA = aiClubIds.get(0);
        UUID clubB = aiClubIds.get(1);
        LocalDate matchDate = LocalDate.now();

        Fixture firstLeg = Fixture.create(saveGameId, competitionId, 903, clubA, clubB, matchDate);
        firstLeg.setTieId(UUID.randomUUID());
        firstLeg.setLeg(1);

        fixtureSimulator.simulateRound(save, competitionId, List.of(firstLeg));

        List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(firstLeg.getId()));
        assertThat(events).noneMatch(e -> EventType.EXTRA_TIME_START.name().equals(e.getType()));
    }

    @Test
    void aPlainLeagueFixtureNeverGoesToExtraTimeRegardlessOfScoreline() {
        UUID clubA = aiClubIds.get(0);
        UUID clubB = aiClubIds.get(1);
        LocalDate matchDate = LocalDate.now();

        // No tieId/leg set at all — the ordinary league path (round already used by real league
        // fixtures created in createCareer(), so pick a round number well clear of those).
        Fixture leagueFixture = Fixture.create(saveGameId, competitionId, 904, clubA, clubB, matchDate);

        fixtureSimulator.simulateRound(save, competitionId, List.of(leagueFixture));

        assertThat(leagueFixture.getPenaltyHome()).isNull();
        assertThat(leagueFixture.getPenaltyAway()).isNull();
        List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(leagueFixture.getId()));
        assertThat(events).noneMatch(e -> EventType.EXTRA_TIME_START.name().equals(e.getType()));
        assertThat(events).allSatisfy(e -> assertThat(e.getMinute()).isBetween(1, MatchMath.MINUTES));
    }
}
