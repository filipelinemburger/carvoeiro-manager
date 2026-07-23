package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedPlayer;
import org.junit.jupiter.api.Test;

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

        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(p -> roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition())));
        TacticalProfile profile = new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO,
                AttackZone.MISTO, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());
        return strengthCalc.compute(xi, profile);
    }

    @Test
    void favoriteBeatsUnderdogInRealisticBand() {
        List<SeedClub> ranked = rankedByStrength();
        TeamMatchInput strong = strength(ranked.get(0));
        TeamMatchInput weak = strength(ranked.get(ranked.size() - 1));

        int favWins = 0;
        int games = 0;
        for (int i = 0; i < SIMS; i++) {
            var atHome = engine.simulate(strong, weak, 1000L + i);   // favorite home
            if (atHome.homeGoals() > atHome.awayGoals()) favWins++;
            games++;
            var away = engine.simulate(weak, strong, 5000L + i);     // favorite away
            if (away.awayGoals() > away.homeGoals()) favWins++;
            games++;
        }
        double rate = favWins / (double) games;
        assertThat(rate).as("favorite win-rate").isBetween(0.60, 0.70);
    }

    @Test
    void equalTeamsHaveRealisticHomeEdge() {
        List<SeedClub> ranked = rankedByStrength();
        TeamMatchInput mid = strength(ranked.get(ranked.size() / 2));

        int homeWins = 0;
        int draws = 0;
        for (int i = 0; i < SIMS; i++) {
            var m = engine.simulate(mid, mid, 9000L + i);
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
                    var m = engine.simulate(teams.get(a), teams.get(b), 13000L + a * 100_000L + b * 1000L + i);
                    totalGoals += m.homeGoals() + m.awayGoals();
                    games++;
                }
            }
        }
        double mean = totalGoals / (double) games;
        assertThat(mean).as("mean goals per match").isBetween(2.4, 3.0);
    }
}
