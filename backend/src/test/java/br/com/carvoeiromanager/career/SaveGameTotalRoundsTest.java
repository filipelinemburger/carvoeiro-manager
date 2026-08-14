package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.competition.FixtureScheduler;
import br.com.carvoeiromanager.competition.ScheduledFixture;
import br.com.carvoeiromanager.seed.SeedCompetition;
import br.com.carvoeiromanager.seed.SeedPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round count an unphased seeded competition gets persisted with. It sizes the per-round
 * buckets {@code SeasonCalendarGenerator} sorts fixtures into, so if it disagrees with what
 * {@code FixtureScheduler} actually emits, career creation dies on an IndexOutOfBounds. Neither
 * {@code SeedPhaseValidator} (which returns early for unphased competitions) nor the scheduler
 * itself rejects an odd field any more, so this is the only place the arithmetic is checked.
 */
class SaveGameTotalRoundsTest {

    private static SeedCompetition league(int members, List<SeedPhase> phases) {
        List<String> shortNames = IntStream.range(0, members).mapToObj(i -> "C" + i).toList();
        return new SeedCompetition("Liga", "LEAGUE", 2026, shortNames, phases);
    }

    @ParameterizedTest
    @CsvSource({"2, 2", "4, 6", "20, 38"})
    void anUnphasedEvenLeagueKeepsTheLegacyDoubleRoundRobinCount(int members, int expectedRounds) {
        // Given an unphased league with an even number of members
        SeedCompetition seedComp = league(members, null);

        // When its season length is computed
        // Then it is the pre-change (members - 1) * 2
        assertThat(SaveGameService.totalRoundsFor(seedComp))
                .as("%d members", members).isEqualTo(expectedRounds);
    }

    @ParameterizedTest
    @CsvSource({"3, 6", "5, 10", "13, 26", "19, 38"})
    void anUnphasedOddLeagueGetsTheExtraByeRoundPerLeg(int members, int expectedRounds) {
        // Given an unphased league with an odd number of members, which cannot pair everyone off
        // every round and so needs one bye round per leg
        SeedCompetition seedComp = league(members, null);

        // When its season length is computed
        // Then it is members * 2, not the (members - 1) * 2 an even field would take
        assertThat(SaveGameService.totalRoundsFor(seedComp))
                .as("%d members", members).isEqualTo(expectedRounds);
        assertThat(SaveGameService.totalRoundsFor(seedComp))
                .as("%d members must not undercount", members).isNotEqualTo((members - 1) * 2);
    }

    @Test
    void anUnphasedLeagueIsLongEnoughToHoldEveryRoundTheSchedulerEmits() {
        // Given a 19-member unphased league — the size that used to size byRound at 36 while the
        // scheduler emitted rounds 0..37
        SeedCompetition seedComp = league(19, null);

        // When the persisted round count is compared with the scheduler's highest round index
        int totalRounds = SaveGameService.totalRoundsFor(seedComp);
        int highestRound = new FixtureScheduler().doubleRoundRobin(19).stream()
                .mapToInt(ScheduledFixture::round)
                .max().orElseThrow();

        // Then every emitted round has a bucket to land in
        assertThat(highestRound).isLessThan(totalRounds);
    }

    @Test
    void aPhasedCompetitionSumsItsPhaseRounds() {
        // Given a phased competition
        SeedCompetition seedComp = league(20, List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 19, 8),
                new SeedPhase("Mata-mata", "KNOCKOUT", 2, 6, 1)));

        // When its season length is computed
        // Then it is the sum of the phases' rounds, ignoring the member count entirely
        assertThat(SaveGameService.totalRoundsFor(seedComp)).isEqualTo(25);
    }

    @Test
    void anEmptyPhaseListFallsBackToTheUnphasedCount() {
        // Given a competition with an empty (rather than null) phase list
        SeedCompetition seedComp = league(13, List.of());

        // When its season length is computed
        // Then it is treated as unphased
        assertThat(SaveGameService.totalRoundsFor(seedComp)).isEqualTo(26);
    }
}
