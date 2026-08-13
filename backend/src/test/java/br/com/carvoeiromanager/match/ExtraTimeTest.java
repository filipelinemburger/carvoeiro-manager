package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit-level determinism guard for {@link MatchSimulationService}'s extra-time bound. No
 * Spring context, no database — mirrors {@link MatchSimulationServiceTest}'s pattern so the
 * golden values are reproducible across runs (a {@code Fixture}/DB-backed test would depend on
 * random {@code UUID}s baked into the match seed, which are NOT stable across test runs).
 *
 * <p>{@code GOLDEN_*} below were captured by running the engine BEFORE this task's changes (see
 * task-7-report.md for the exact command/output). They must still hold after adding extra time,
 * because the 4-arg {@code simulate} overload always delegates with {@code finalMinute =
 * MatchMath.MINUTES} — the loop bound, and every RNG draw inside it, is untouched for a normal
 * (non-extended) call.
 *
 * <p>Fixture/tie-level behavioural coverage (does a level second leg actually trigger extra
 * time, does a decided one not, etc.) lives in {@link FixtureSimulatorExtraTimeIT}, which needs
 * real persisted {@code Fixture} rows to express {@code tieId}/{@code leg}/aggregate.
 */
class ExtraTimeTest {

    private final MatchSimulationService engine = new MatchSimulationService();

    private TeamMatchInput input(double attack, double defense) {
        return new TeamMatchInput(attack, defense, 1.0, 1.0, 0.5, 0.5, 0.82, 0.15,
                Mentality.BALANCED, GameStyle.EQUILIBRADO);
    }

    /**
     * Captured from the CURRENT (pre-extra-time) engine with:
     * {@code engine.simulate(input(72, 60), input(58, 66), Weather.neutral(), seed)}
     * for each seed below. See task-7-report.md for the literal command/output that produced
     * these — NOT synthesized.
     */
    private static final long[] SEEDS = {1L, 2L, 3L, 42L, 100L, 777L, 12345L, 999999L};
    // Recomputed for spec 2026-08-13 §9 (BASE_CARD_RATE 0.02 -> 0.015): the per-minute RNG draw
    // order means a card draw's outcome shifts every subsequent draw in the same seeded stream,
    // so goal counts on some seeds changed even though goal logic itself was untouched.
    private static final int[] GOLDEN_HOME = {0, 4, 4, 1, 3, 2, 1, 0};
    private static final int[] GOLDEN_AWAY = {4, 1, 2, 1, 0, 0, 1, 3};

    @Test
    void aLeagueMatchIsByteIdenticalToTheGoldenResult() {
        TeamMatchInput home = input(72, 60);
        TeamMatchInput away = input(58, 66);
        for (int i = 0; i < SEEDS.length; i++) {
            SimulatedMatch m = engine.simulate(home, away, Weather.neutral(), SEEDS[i]);
            assertThat(m.homeGoals()).as("seed %d home goals", SEEDS[i]).isEqualTo(GOLDEN_HOME[i]);
            assertThat(m.awayGoals()).as("seed %d away goals", SEEDS[i]).isEqualTo(GOLDEN_AWAY[i]);
        }
    }

    /** Seeds 42 and 12345 above are draws (1-1) — confirms a level league match stays untouched too. */
    @Test
    void aDrawnLeagueMatchStaysADrawAndEmitsNoExtraTimeMarker() {
        TeamMatchInput home = input(72, 60);
        TeamMatchInput away = input(58, 66);
        SimulatedMatch m = engine.simulate(home, away, Weather.neutral(), 42L);
        assertThat(m.homeGoals()).isEqualTo(m.awayGoals());
        assertThat(m.events()).noneMatch(e -> e.type() == EventType.EXTRA_TIME_START);
        assertThat(m.events()).allSatisfy(e -> assertThat(e.minute()).isBetween(1, 90));
    }

    @Test
    void the5ArgOverloadWithMinutesIsByteIdenticalToThe4ArgOverload() {
        TeamMatchInput home = input(72, 60);
        TeamMatchInput away = input(58, 66);
        for (long seed : SEEDS) {
            SimulatedMatch fourArg = engine.simulate(home, away, Weather.neutral(), seed);
            SimulatedMatch fiveArg = engine.simulate(home, away, Weather.neutral(), seed, MatchMath.MINUTES);
            assertThat(fiveArg).isEqualTo(fourArg);
        }
    }

    /**
     * Extending the bound to EXTRA_TIME_END reproduces the IDENTICAL minutes 1-90 (same seed,
     * same Random(seed) replay from minute 1) and only then continues — proving the extension
     * never perturbs the normal-time result, only appends to it.
     */
    @Test
    void extendingToExtraTimeReproducesTheIdenticalFirst90Minutes() {
        TeamMatchInput home = input(72, 60);
        TeamMatchInput away = input(58, 66);
        for (long seed : SEEDS) {
            SimulatedMatch normal = engine.simulate(home, away, Weather.neutral(), seed);
            SimulatedMatch extended = engine.simulate(home, away, Weather.neutral(), seed, MatchMath.EXTRA_TIME_END);

            assertThat(extended.events().stream()
                            .filter(e -> e.minute() <= MatchMath.MINUTES && e.type() != EventType.EXTRA_TIME_START)
                            .toList())
                    .as("seed %d minutes 1-90", seed)
                    .isEqualTo(normal.events());

            long homeGoalsThrough90 = extended.events().stream()
                    .filter(e -> e.home() && e.type().isGoal() && e.minute() <= MatchMath.MINUTES).count();
            long awayGoalsThrough90 = extended.events().stream()
                    .filter(e -> !e.home() && e.type().isGoal() && e.minute() <= MatchMath.MINUTES).count();
            assertThat(homeGoalsThrough90).isEqualTo(normal.homeGoals());
            assertThat(awayGoalsThrough90).isEqualTo(normal.awayGoals());

            assertThat(extended.events()).anyMatch(e -> e.type() == EventType.EXTRA_TIME_START
                    && e.minute() == MatchMath.MINUTES);
            assertThat(extended.events()).allSatisfy(e -> assertThat(e.minute()).isBetween(1, MatchMath.EXTRA_TIME_END));
        }
    }
}
