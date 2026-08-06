package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the phase-scoped overload of {@link StandingsService#computeStandings}. The Catarinense
 * fixture (12 members, a round-robin first phase followed by knockout phases) and Série B
 * (20 members, no phases at all) are the same seeded competitions used elsewhere in this
 * package (see {@link TopScorersServiceIT}), so this test reuses that career-creation pattern.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class StandingsServiceIT {

    @Autowired StandingsService standingsService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("standings_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private void advanceToFinish(Career c) {
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
    }

    @Test
    void standingsForAPhaseCountOnlyThatPhasesFixtures() {
        // Given: a career whose Catarinense competition has run to completion — its round-robin
        // first phase (12 clubs, 11 rounds) is done, and the knockout phases after it (including
        // the quarterfinals) have also been played.
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        UUID firstPhaseId = competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competitionId)
                .get(0).getId();
        advanceToFinish(c);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        // When: standings are computed scoped to the first phase only
        List<StandingRow> rows = standingsService.computeStandings(save, competitionId, firstPhaseId, null);

        // Then: every one of the 12 clubs has played exactly 11 matches — the knockout rounds
        // that follow (quarterfinals onward) are excluded from this phase's table.
        assertThat(rows).hasSize(12);
        assertThat(rows).allSatisfy(r -> assertThat(r.played()).isEqualTo(11));
    }

    @Test
    void standingsWithANullPhaseIdCoverTheWholeCompetition() {
        // Given a plain league competition (Série B, no phases at all)
        Career c = createCareer();
        UUID serieBId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Série B"))
                .findFirst().orElseThrow().getId();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        // When: standings are computed with no phase filter
        List<StandingRow> rows = standingsService.computeStandings(save, serieBId, null, null);

        // Then: all 20 members appear, exactly like the pre-existing 3-arg overload
        assertThat(rows).hasSize(20);
    }

    @Test
    void threeArgOverloadStillDelegatesToTheWholeCompetition() {
        // Given a plain league competition (Série B, no phases at all)
        Career c = createCareer();
        UUID serieBId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Série B"))
                .findFirst().orElseThrow().getId();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        // When/Then: the pre-existing overload (no phaseId) behaves exactly as before
        List<StandingRow> rows = standingsService.computeStandings(save, serieBId, (java.time.LocalDate) null);
        assertThat(rows).hasSize(20);
    }

    @Test
    void anUnknownPhaseIdIsRejectedRatherThanSilentlyEmptyingTheTable() {
        // Given a real competition and a phase id that belongs to nothing
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        // When / Then: it must fail loudly. Filtering on it would otherwise match no fixture and
        // return a full 12-club table with played=0 for everyone — a plausible-looking lie.
        assertThatThrownBy(() ->
                standingsService.computeStandings(save, competitionId, UUID.randomUUID(), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aPhaseFromAnotherCompetitionIsRejected() {
        // Given the Catarinense's first phase and Série B's id
        Career c = createCareer();
        UUID catarinenseId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        UUID serieBId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Série B"))
                .findFirst().orElseThrow().getId();
        UUID catarinensePhaseId = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(catarinenseId).get(0).getId();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        // When / Then: a phase id is only meaningful inside its own competition
        assertThatThrownBy(() ->
                standingsService.computeStandings(save, serieBId, catarinensePhaseId, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void phaseAndAsOfDateFiltersComposeWithoutDroppingOrDoubleCounting() {
        // Given the Catarinense's completed first phase (12 clubs, 11 rounds from 15 Jan,
        // one match-unit every 5 days interleaved with Série B)
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getId();
        UUID firstPhaseId = competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competitionId)
                .get(0).getId();
        advanceToFinish(c);
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        List<StandingRow> full = standingsService.computeStandings(save, competitionId, firstPhaseId, null);
        List<StandingRow> bounded = standingsService.computeStandings(
                save, competitionId, firstPhaseId, LocalDate.of(2026, 2, 1));

        // Then: both filters apply together — the bounded table is a strict prefix of the phase's
        // fixtures, never the whole competition and never the unbounded phase.
        assertThat(full).allSatisfy(r -> assertThat(r.played()).isEqualTo(11));
        assertThat(bounded).hasSize(12);
        assertThat(bounded.stream().mapToInt(StandingRow::played).sum())
                .isLessThan(full.stream().mapToInt(StandingRow::played).sum());
        assertThat(bounded).allSatisfy(r -> assertThat(r.played()).isLessThanOrEqualTo(11));
    }
}
