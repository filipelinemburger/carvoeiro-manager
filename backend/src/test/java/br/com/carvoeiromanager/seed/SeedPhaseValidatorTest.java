package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each case here is a config that used to fail later and worse — mid-season inside an advance
 * that rolls back, or at rollover with the career already unable to finish.
 */
class SeedPhaseValidatorTest {

    private static final List<String> TWELVE = List.of(
            "AVA", "CAM", "CON", "MAR", "BRU", "JOI", "CHA", "CAR", "BAR", "FIG", "CRI", "SCA");

    private static SeedCompetition withPhases(List<SeedPhase> phases) {
        return new SeedCompetition("Campeonato Catarinense", "LEAGUE", 2026, TWELVE, phases);
    }

    private static List<SeedPhase> validCatarinense() {
        return List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 11, 8),
                new SeedPhase("Quartas de final", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Final", "KNOCKOUT", 2, 2, null));
    }

    @Test
    void theShippedCatarinenseConfigurationIsValid() {
        assertThatCode(() -> SeedPhaseValidator.validate(withPhases(validCatarinense())))
                .doesNotThrowAnyException();
    }

    @Test
    void aCompetitionWithNoPhasesIsValid() {
        // Série B: a plain league, nothing to check.
        assertThatCode(() -> SeedPhaseValidator.validate(
                new SeedCompetition("Série B", "LEAGUE", 2026, TWELVE, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void qualifiersMustBeAPowerOfTwo() {
        // 6 qualifiers blows up inside knockoutPairings mid-season, in an advance that rolls back.
        List<SeedPhase> phases = List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 11, 6),
                new SeedPhase("Quartas de final", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("power of two");
    }

    @Test
    void roundRobinRoundsMustMatchTheMemberCount() {
        // 10 rounds for 12 clubs throws IndexOutOfBounds out of the calendar generator.
        List<SeedPhase> phases = List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 10, 8),
                new SeedPhase("Quartas de final", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Final", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("produce 11");
    }

    @Test
    void aKnockoutPhaseMustBeTwoLeggedOverTwoRounds() {
        List<SeedPhase> phases = List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 11, 8),
                new SeedPhase("Quartas de final", "KNOCKOUT", 1, 1, null),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Final", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two-legged");
    }

    @Test
    void theKnockoutPhaseCountMustReachExactlyOneWinner() {
        // 8 qualifiers need 3 rounds; 2 leaves four clubs standing and no champion.
        List<SeedPhase> phases = List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 11, 8),
                new SeedPhase("Quartas de final", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 3 knockout phase");
    }

    @Test
    void theFirstPhaseCannotBeAKnockout() {
        // PhaseResolver only materializes the phase AFTER a completed one, so a knockout first
        // phase never gets fixtures at all.
        List<SeedPhase> phases = List.of(
                new SeedPhase("Final", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first phase must be ROUND_ROBIN");
    }

    @Test
    void aKnockoutPhaseMustNotDeclareQualifiers() {
        List<SeedPhase> phases = List.of(
                new SeedPhase("Primeira Fase", "ROUND_ROBIN", 1, 11, 8),
                new SeedPhase("Quartas de final", "KNOCKOUT", 2, 2, 4),
                new SeedPhase("Semifinal", "KNOCKOUT", 2, 2, null),
                new SeedPhase("Final", "KNOCKOUT", 2, 2, null));
        assertThatThrownBy(() -> SeedPhaseValidator.validate(withPhases(phases)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not declare qualifiers");
    }
}
