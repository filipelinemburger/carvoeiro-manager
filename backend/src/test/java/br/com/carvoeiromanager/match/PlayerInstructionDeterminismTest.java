package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleAssignment;
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
    // Recomputed for spec 2026-08-13 §9 (BASE_CARD_RATE 0.02 -> 0.015). The card golden shrank
    // (5 yellows + 1 red -> 3 yellows: fewer card rolls fire at the lower rate) and, as a
    // consequence, the OLD golden's 35:A:RED_CARD:3 no longer occurs.
    //
    // The foul golden below moved too, but NOT because fouls share a stream with cards: they do
    // not. `LiveMatchEngine` draws fouls from a dedicated `foulRng` (seeded independently, see
    // `maybeFoul`'s javadoc — "drawn entirely from the dedicated foul stream ... so the
    // match/attribution/injury streams are untouched"), and `foulRng` is never touched by
    // `maybeCard`. If cards and fouls shared a stream, that sharing would itself BE the
    // cross-stream leak this test class exists to catch.
    //
    // The real mechanism: `pickByWeight` (used for both card and foul attribution) draws
    // `rng.nextInt(totalWeight)` over `side.onPitch` — the CURRENT on-pitch pool, not a fixed
    // roster. Losing that 35th-minute red card means the away side fields 11 players from minute
    // 36 onward instead of 10, which changes `totalWeight` and the index each weight-bucket lands
    // on. So an UNCHANGED `foulRng` draw resolves to a DIFFERENT player once the pool size
    // differs — no extra draws are consumed, no minute shifts. That is exactly why every foul
    // MINUTE below is unchanged from the pre-0.015 golden, only five attributions moved (36, 41,
    // 45, 53, 60), and every one of those five is on the away side, at or after minute 36.
    //
    // The contrapositive is the guard this comment exists to state: a foul-golden drift NOT
    // accompanied by a change in the on-pitch pool (i.e. no red card gained/lost on the relevant
    // side, at a minute before the drifted attributions) is NOT explained by this mechanism and
    // must NOT be re-baselined without finding an actual cause — it would be a real `foulRng`
    // leak (or a break in `maybeFoul`/`pickByWeight` itself), which is precisely the class of bug
    // this test exists to catch.
    private static final String GOLDEN_CARDS =
            "42:A:YELLOW_CARD:3|63:A:YELLOW_CARD:1|68:H:YELLOW_CARD:9";
    private static final String GOLDEN_FOULS =
            "5:A:FOUL:7|6:A:FOUL:10|14:H:FOUL:1|20:H:FOUL:4|21:H:FOUL:6|24:A:FOUL:4|27:H:FOUL:7|27:A:FOUL:9|"
            + "30:A:FOUL:1|33:H:FOUL:4|35:H:FOUL:1|36:A:FOUL:3|38:H:FOUL:5|41:A:FOUL:4|45:A:FOUL:6|47:H:FOUL:3|"
            + "49:H:FOUL:6|53:A:FOUL:9|60:A:FOUL:4|61:H:FOUL:2|62:H:FOUL:3|68:H:FOUL:10|76:H:FOUL:5|78:H:FOUL:8|"
            + "83:H:FOUL:9|86:H:FOUL:1";
    // Instant engine (TeamStrengthCalculator -> MatchSimulationService), seeds 0..11.
    // Re-baselined when MatchMath.ANCHOR moved 1.35 -> 1.44 in the live-weather calibration task
    // (MatchCalibrationTest): raising ANCHOR lifts every simulated match's baseline xG, so this is
    // a legitimate consequence of a global scoring recalibration, not an engine defect. Only the
    // seed-10 result changed (0-0 -> 0-1); the other eleven seeds are byte-identical to the prior
    // golden.
    // Re-baselined again for spec 2026-08-13 §9 (BASE_CARD_RATE 0.02 -> 0.015): the instant engine
    // draws its card roll from the same per-minute stream as its scoring rolls, so a changed card
    // outcome shifts every later draw for that seed. Scoring logic itself is untouched.
    private static final String GOLDEN_INSTANT = "1-1|0-4|0-1|2-2|3-2|2-1|2-1|3-0|2-0|1-1|0-0|0-0|";

    private final br.com.carvoeiromanager.staff.StaffEffectsService staff =
            Mockito.mock(br.com.carvoeiromanager.staff.StaffEffectsService.class);
    private final LiveMatchEngine engine =
            new LiveMatchEngine(new TeamStrengthCalculator(), new FitnessCalculator(), staff);
    {
        Mockito.when(staff.injuryRiskMultiplierFor(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1.0);
        // Neutral coach multiplier: this mock has no stub without it, and Mockito's unstubbed
        // double default (0.0) would zero out attack/defense, breaking every golden signature below.
        Mockito.when(staff.coachStrengthMultiplierFor(org.mockito.ArgumentMatchers.any())).thenReturn(1.0);
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
        // Legacy archetype + canonical focus + familiarity 2 = the regression contract, so the
        // golden signatures captured against the old PlayerRole model must reproduce verbatim.
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(xi);
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
                defaultProfile(homeXi), defaultProfile(awayXi), fitness, List.of(), List.of(), null,
                Weather.neutral(), false, null, null);
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
            SimulatedMatch m = instant.simulate(hIn, aIn, Weather.neutral(), seed);
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
