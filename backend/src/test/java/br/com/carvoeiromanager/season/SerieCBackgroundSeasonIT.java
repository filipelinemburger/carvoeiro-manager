package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.KnockoutTie;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TieResolver;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Série C plays itself out in the background over a full season.
 *
 * <p>The load-bearing assertion is the semifinalist one. The real regulation promotes both
 * quadrangular winners AND both runners-up — four clubs, decided in the second phase and not in
 * the final (REC Art. 5º). The engine cannot express quadrangulares, so it runs a seeded knockout
 * over the same eight qualifiers, and the four semifinalists are the four the promotion slice will
 * promote. If that mapping ever stops holding, promotion silently promotes the wrong clubs.
 *
 * <p>Distinct from {@link BackgroundCompetitionIT}, which builds a small synthetic phased
 * competition and sweeps it in one {@code resolveDue} call to pin the resolver's ordering and
 * flush-visibility properties. This one takes the shipped seed exactly as a player gets it and
 * drives the real advance loop, so the bracket has to survive being materialized in one
 * transaction and played in a later one.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
// Needs the real seed: Série C is not in the test profile's trimmed competition set.
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class SerieCBackgroundSeasonIT {

    private static final int MEMBERS = 20;
    private static final int FIRST_PHASE_ROUNDS = MEMBERS - 1;
    private static final int QUALIFIERS = 8;
    private static final int SEMIFINALISTS = 4;
    private static final int FINALISTS = 2;

    /**
     * A whole season's advances (managed rounds + transfer stops) with room to spare. The plan's
     * listing had an unguarded {@code while} loop; a regression that stops the save reaching
     * FINISHED must fail with a clear message rather than hang the suite — same reason
     * {@link FullPyramidSeasonIT} carries this constant.
     */
    private static final int ADVANCE_GUARD = 500;

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired StandingsService standingsService;

    @Test
    void serieCPlaysAFullSeasonAndProducesExactlyFourSemifinalists() {
        User user = User.create("seriec_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveId = save.id();

        Competition serieC = competitionRepository.findBySaveGameId(saveId).stream()
                .filter(c -> c.getName().contains("Série C"))
                .findFirst().orElseThrow();

        // The managed club is not in it — that is what makes it a background competition.
        assertThat(competitionClubRepository.findByCompetitionId(serieC.getId()))
                .hasSize(MEMBERS)
                .noneMatch(cc -> cc.getClubId().equals(save.managedClubId()));

        // Drive the whole season from the managed club's calendar; background rounds resolve as
        // their dates pass.
        int advances = 0;
        while (saveGameService.requireOwned(saveId, user.getId()).getStatus()
                != SaveGameStatus.FINISHED && advances++ < ADVANCE_GUARD) {
            seasonAdvanceService.advance(saveId, user.getId());
        }
        assertThat(advances).isLessThan(ADVANCE_GUARD);
        assertThat(saveGameService.requireOwned(saveId, user.getId()).getStatus())
                .isEqualTo(SaveGameStatus.FINISHED);

        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveId, serieC.getId());
        assertThat(fixtures).isNotEmpty()
                .allMatch(Fixture::isPlayed, "every Série C fixture must be played");

        List<CompetitionPhase> phases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(serieC.getId());
        assertThat(phases).hasSize(4);

        // First phase: a real 20-row table, everyone on 19 games, goals that balance.
        List<StandingRow> table = standingsService.getStandings(
                saveId, serieC.getId(), phases.get(0).getId(), user.getId());
        assertThat(table).hasSize(MEMBERS);
        assertThat(table).allMatch(r -> r.played() == FIRST_PHASE_ROUNDS);
        assertThat(table.stream().mapToInt(StandingRow::goalsFor).sum())
                .isEqualTo(table.stream().mapToInt(StandingRow::goalsAgainst).sum());
        assertThat(table.stream().mapToInt(StandingRow::goalsFor).sum()).isPositive();

        // The bracket: 8 qualifiers -> 4 quarterfinal ties -> 4 semifinalists.
        Set<UUID> quarterfinalists = clubsIn(fixtures, phases.get(1));
        Set<UUID> semifinalists = clubsIn(fixtures, phases.get(2));
        Set<UUID> finalists = clubsIn(fixtures, phases.get(3));
        assertThat(quarterfinalists).hasSize(QUALIFIERS);
        assertThat(semifinalists).as("the four clubs the promotion slice will promote")
                .hasSize(SEMIFINALISTS);
        assertThat(finalists).hasSize(FINALISTS);
        assertThat(quarterfinalists).containsAll(semifinalists);
        assertThat(semifinalists).containsAll(finalists);

        // The eight qualifiers are the top eight of the first-phase table, not an arbitrary set.
        assertThat(quarterfinalists).isEqualTo(table.subList(0, QUALIFIERS).stream()
                .map(StandingRow::clubId).collect(Collectors.toSet()));

        // And the chain actually ends: the final resolves to one champion, drawn from the two
        // finalists. That is the last link promotion depends on — a bracket that materializes but
        // never decides anything would satisfy every assertion above.
        List<KnockoutTie> finalTies = TieResolver.tiesOf(fixtures.stream()
                .filter(f -> phases.get(3).getId().equals(f.getPhaseId())).toList());
        assertThat(finalTies).hasSize(1);
        assertThat(TieResolver.winner(finalTies.get(0))).isIn(finalists);
    }

    private Set<UUID> clubsIn(List<Fixture> fixtures, CompetitionPhase phase) {
        assertThat(phase.getKind()).isEqualTo(PhaseKind.KNOCKOUT);
        return fixtures.stream()
                .filter(f -> phase.getId().equals(f.getPhaseId()))
                .flatMap(f -> Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .collect(Collectors.toSet());
    }
}
