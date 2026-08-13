package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedPlayer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration harness AND regression guard for the recalibrated scoring math. Builds real seeded
 * rosters, runs the deterministic instant engine over thousands of matchups, and asserts the
 * "realistic league" target bands. Pure unit test — no Spring context, no database.
 */
class MatchCalibrationTest {

    private static final int SIMS = 2000;

    private final SeedLoader seedLoader = new SeedLoader();
    private final OverallCalculator overallCalculator = new OverallCalculator();
    private final TeamStrengthCalculator strengthCalc = new TeamStrengthCalculator();
    private final MatchSimulationService engine = new MatchSimulationService();

    /**
     * The reference squad for the "average matchup" bands, pinned by name rather than by
     * position in the pool.
     *
     * <p>These bands were tuned against Náutico, which happened to be {@code
     * rankedByStrength().get(size / 2)} when the pool held 28 clubs. Indexing into the pool makes
     * the reference drift with the seed data: adding the three 2026 Catarinense clubs moved the
     * median to São Bernardo, a weaker squad, which dropped mean goals to 1.90 against a 2.4–3.0
     * band and pushed the draw rate to 0.307 against 0.24–0.28 — with the engine completely
     * unchanged. Retuning the constants to chase that would have altered real match balance to
     * compensate for a test-sampling artefact.
     *
     * <p>Pinning by name keeps these tests measuring the engine. It matters increasingly: the
     * roadmap adds Série A, C, D and further state championships, and every one of those would
     * otherwise re-anchor this test.
     */
    private static final String REFERENCE_CLUB = "NAU";

    /**
     * The favourite/underdog pair, pinned by name for exactly the reason above — and this is the
     * drift that javadoc predicted. This test used to take the extremes of the whole pool, which
     * held one tier of Brazilian football. Série A arrived and the extremes became Flamengo
     * against a small state club: a 32-point gap the engine correctly turned into an 0.82
     * favourite win-rate, against a band tuned for 0.60–0.70. Nothing about the engine changed.
     *
     * <p>Criciúma against Santa Catarina reproduces the matchup the band was tuned on (the old
     * extremes were Criciúma and Chapecoense, a 23-point gap; Chapecoense has since been rebuilt
     * as a Série A side, and Santa Catarina sits within half a point of where it was). A
     * cross-tier thrashing is a real result, not a miscalibration — it is just not what this
     * test measures.
     */
    private static final String FAVORITE_CLUB = "CRI";
    private static final String UNDERDOG_CLUB = "SCA";

    private SeedClub referenceClub() {
        return clubByShortName(REFERENCE_CLUB);
    }

    private SeedClub clubByShortName(String shortName) {
        return seedLoader.loadCareer().clubPool().stream()
                .filter(c -> shortName.equals(c.shortName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Calibration club " + shortName + " is no longer in the seed pool"));
    }

    private List<SeedClub> rankedByStrength() {
        return seedLoader.loadCareer().clubPool().stream()
                .sorted(Comparator.comparingDouble(this::squadAvgOverall).reversed())
                .toList();
    }

    private double squadAvgOverall(SeedClub club) {
        return club.players().stream().mapToInt(this::overallOf).average().orElse(0);
    }

    private int overallOf(SeedPlayer sp) {
        return overallCalculator.compute(Position.valueOf(sp.position()),
                sp.pace(), sp.finishing(), sp.passing(), sp.tackling(), sp.goalkeeping(), sp.stamina());
    }

    private Player toPlayer(SeedPlayer sp) {
        Position pos = Position.valueOf(sp.position());
        // Every seeded club carries hand-authored attributes (Catarinense data pass), so this is non-null.
        Attributes attrs = sp.attributes().toAttributes(sp.pace(), sp.finishing(), sp.passing(),
                sp.tackling(), sp.goalkeeping(), sp.stamina());
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), sp.name(), pos,
                sp.age(), sp.nationality(), attrs, overallOf(sp));
    }

    /** Best GK + top 10 outfield by overall — a strong, valid XI without formation plumbing. */
    private TeamMatchInput strength(SeedClub club) {
        List<Player> squad = club.players().stream().map(this::toPlayer).toList();
        Player gk = squad.stream().filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall)).orElseThrow();
        List<Player> xi = new ArrayList<>();
        xi.add(gk);
        squad.stream().filter(p -> p.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .limit(10)
                .forEach(xi::add);

        // Legacy archetype + canonical focus + familiarity 2: the calibration bands were tuned
        // against the retired PlayerRole model and must survive the swap unchanged.
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(xi);
        TacticalProfile profile = new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO,
                AttackZone.MISTO, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());
        // Deliberately WITHOUT withCoachMultiplier: these bands measure squad talent, and folding
        // the head coach in would let a staffing change silently re-tune the scoring curve. The
        // seed's league-wide mean multiplier is 0.9983 and the per-side xG swing about +/-0.08,
        // so the aggregate bands are unaffected either way — this is an exclusion, not an oversight.
        return strengthCalc.compute(xi, profile);
    }

    @Test
    void favoriteBeatsUnderdogInRealisticBand() {
        TeamMatchInput strong = strength(clubByShortName(FAVORITE_CLUB));
        TeamMatchInput weak = strength(clubByShortName(UNDERDOG_CLUB));

        int favWins = 0;
        int games = 0;
        for (int i = 0; i < SIMS; i++) {
            var atHome = engine.simulate(strong, weak, Weather.neutral(), 1000L + i);   // favorite home
            if (atHome.homeGoals() > atHome.awayGoals()) favWins++;
            games++;
            var away = engine.simulate(weak, strong, Weather.neutral(), 5000L + i);     // favorite away
            if (away.awayGoals() > away.homeGoals()) favWins++;
            games++;
        }
        double rate = favWins / (double) games;
        assertThat(rate).as("favorite win-rate").isBetween(0.60, 0.70);
    }

    @Test
    void equalTeamsHaveRealisticHomeEdge() {
        TeamMatchInput mid = strength(referenceClub());

        int homeWins = 0;
        int draws = 0;
        for (int i = 0; i < SIMS; i++) {
            var m = engine.simulate(mid, mid, Weather.neutral(), 9000L + i);
            if (m.homeGoals() > m.awayGoals()) homeWins++;
            else if (m.homeGoals() == m.awayGoals()) draws++;
        }
        assertThat(homeWins / (double) SIMS).as("home win-rate").isBetween(0.42, 0.48);
        assertThat(draws / (double) SIMS).as("draw-rate").isBetween(0.24, 0.28);
    }

    @Test
    void meanGoalsPerMatchAreRealistic() {
        List<SeedClub> ranked = rankedByStrength();
        List<TeamMatchInput> teams = List.of(
                strength(ranked.get(0)),
                strength(ranked.get(ranked.size() / 2)),
                strength(ranked.get(ranked.size() - 1)));

        long totalGoals = 0;
        int games = 0;
        for (int a = 0; a < teams.size(); a++) {
            for (int b = 0; b < teams.size(); b++) {
                for (int i = 0; i < SIMS / 4; i++) {
                    var m = engine.simulate(teams.get(a), teams.get(b), Weather.neutral(),
                            13000L + a * 100_000L + b * 1000L + i);
                    totalGoals += m.homeGoals() + m.awayGoals();
                    games++;
                }
            }
        }
        double mean = totalGoals / (double) games;
        assertThat(mean).as("mean goals per match").isBetween(2.4, 3.0);
    }

    /**
     * Well-spread synthetic fixture id, mirroring {@code WeatherGeneratorTest.fixtureId}.
     * {@code WeatherGenerator} seeds its RNG from {@code msb ^ lsb}, so a naive {@code new UUID(i, i)}
     * XORs to a constant 0 seed for every i, and small sequential seeds correlate on the first draw.
     */
    private static UUID fixtureId(long i) {
        return new UUID(i * 0x9E3779B97F4A7C15L, i * 0xC2B2AE3D27D4EB4FL);
    }

    @Test
    void meanGoalsPerMatchStayInBandWithRealWeather() {
        TeamMatchInput mid = strength(referenceClub());

        long totalGoals = 0;
        int games = 0;
        for (int i = 0; i < SIMS; i++) {
            long seed = 21000L + i;
            LocalDate matchDate = LocalDate.of(2026, 1 + (i % 12), 15);
            Weather weather = WeatherGenerator.forFixture(fixtureId(seed), ClimateZone.SUL, matchDate);
            var m = engine.simulate(mid, mid, weather, seed);
            totalGoals += m.homeGoals() + m.awayGoals();
            games++;
        }
        double mean = totalGoals / (double) games;
        assertThat(mean).as("mean goals per match with real weather").isBetween(2.4, 3.0);
    }
}
