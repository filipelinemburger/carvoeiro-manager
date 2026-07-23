package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-identity regression for PlayerInstruction: with an EMPTY instructions map (the AI/unset case)
 * both engines must reproduce fixed-seed signatures captured from the pre-instruction engine — not
 * just the goal/assist stream (already guarded by {@code LiveMatchEngineTest}'s golden), but the
 * card, foul and instant-score streams that the instruction weights also touch. A drift here means
 * the empty map stopped being the neutral no-op — a real seeded-result break.
 */
class PlayerInstructionDeterminismTest {

    // Golden signatures captured from the pre-instruction engine (seed 1234, default MISTO profile,
    // no per-player instructions). See the temporary capture harness in the task notes.
    private static final String GOLDEN_CARDS =
            "2:A:YELLOW_CARD:3|16:H:YELLOW_CARD:1|22:A:YELLOW_CARD:10|35:A:YELLOW_CARD:3|35:A:RED_CARD:3|66:A:YELLOW_CARD:8";
    private static final String GOLDEN_FOULS =
            "5:A:FOUL:7|6:A:FOUL:10|14:H:FOUL:1|20:H:FOUL:4|21:H:FOUL:6|24:A:FOUL:4|27:H:FOUL:7|27:A:FOUL:9|"
            + "30:A:FOUL:1|33:H:FOUL:4|35:H:FOUL:1|36:A:FOUL:1|38:H:FOUL:5|41:A:FOUL:7|45:A:FOUL:2|47:H:FOUL:3|"
            + "49:H:FOUL:6|53:A:FOUL:1|60:A:FOUL:6|61:H:FOUL:2|62:H:FOUL:3|68:H:FOUL:10|76:H:FOUL:5|78:H:FOUL:8|"
            + "83:H:FOUL:9|86:H:FOUL:1";
    // Instant engine (TeamStrengthCalculator -> MatchSimulationService), seeds 0..11.
    private static final String GOLDEN_INSTANT = "1-3|0-4|1-1|2-2|2-1|2-1|2-1|2-0|2-0|3-2|0-0|0-2|";

    private final br.com.carvoeiromanager.staff.StaffEffectsService staff =
            Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class);
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staff);
    {
        Mockito.when(staff.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
    }

    private static Player p(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    private static List<Player> buildXi() {
        return new ArrayList<>(List.of(
                p(Position.GK, 70),
                p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70),
                p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70),
                p(Position.ATT, 70), p(Position.ATT, 70)));
    }

    // Fixed XI/bench so the fixed-seed signatures are stable (same construction as LiveMatchEngineTest).
    private final List<Player> homeXi = buildXi();
    private final List<Player> awayXi = buildXi();
    private final List<Player> homeBench = List.of(p(Position.MID, 65), p(Position.ATT, 65), p(Position.DEF, 65));

    private TacticalProfile defaultProfile(List<Player> xi) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        // Empty instructions map: the neutral no-op.
        return new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO, "4-4-2",
                Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, Map.of(), Map.of());
    }

    @Test
    void emptyInstructionsKeepTheLiveCardAndFoulStreamsByteIdentical() {
        Map<UUID, Integer> fitness = new HashMap<>();
        homeXi.forEach(pl -> fitness.put(pl.getId(), 100));
        awayXi.forEach(pl -> fitness.put(pl.getId(), 100));
        homeBench.forEach(pl -> fitness.put(pl.getId(), 100));
        LiveMatchConfig cfg = new LiveMatchConfig(1234L, homeXi, awayXi, homeBench, List.of(),
                defaultProfile(homeXi), defaultProfile(awayXi), fitness, List.of(), List.of(), null);
        LiveTimeline t = engine.simulate(cfg);
        assertThat(sig(t, EventType.YELLOW_CARD, EventType.RED_CARD)).isEqualTo(GOLDEN_CARDS);
        assertThat(sig(t, EventType.FOUL)).isEqualTo(GOLDEN_FOULS);
    }

    @Test
    void emptyInstructionsKeepTheInstantScoresByteIdentical() {
        TeamStrengthCalculator calc = new TeamStrengthCalculator();
        Map<UUID, Double> unit = new HashMap<>();
        homeXi.forEach(pl -> unit.put(pl.getId(), 1.0));
        awayXi.forEach(pl -> unit.put(pl.getId(), 1.0));
        TeamMatchInput hIn = calc.compute(homeXi, defaultProfile(homeXi), unit);
        TeamMatchInput aIn = calc.compute(awayXi, defaultProfile(awayXi), unit);
        MatchSimulationService instant = new MatchSimulationService();
        StringBuilder sb = new StringBuilder();
        for (long seed = 0; seed < 12; seed++) {
            SimulatedMatch m = instant.simulate(hIn, aIn, seed);
            sb.append(m.homeGoals()).append('-').append(m.awayGoals()).append('|');
        }
        assertThat(sb.toString()).isEqualTo(GOLDEN_INSTANT);
    }

    private String sig(LiveTimeline t, EventType... types) {
        Set<EventType> set = EnumSet.noneOf(EventType.class);
        for (EventType ty : types) set.add(ty);
        return t.events().stream()
                .filter(e -> set.contains(e.type()))
                .map(e -> {
                    List<Player> xi = e.home() ? homeXi : awayXi;
                    return e.minute() + ":" + (e.home() ? "H" : "A") + ":" + e.type().name()
                            + ":" + idx(xi, e.playerId());
                })
                .collect(Collectors.joining("|"));
    }

    private static String idx(List<Player> xi, UUID id) {
        if (id == null) return "-";
        for (int i = 0; i < xi.size(); i++) if (xi.get(i).getId().equals(id)) return Integer.toString(i);
        return "?";
    }
}
