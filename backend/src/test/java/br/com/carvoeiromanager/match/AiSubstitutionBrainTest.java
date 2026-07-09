package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.AiManagerBrain.Posture;
import br.com.carvoeiromanager.match.AiSubstitutionBrain.Kind;
import br.com.carvoeiromanager.match.AiSubstitutionBrain.SubDecision;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiSubstitutionBrainTest {

    private static Player p(Position pos, int overall) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), pos + "-" + overall, pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, overall);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, distinct overalls so selections are unambiguous. */
    private static List<Player> pitch() {
        return List.of(
                p(Position.GK, 70),
                p(Position.DEF, 68), p(Position.DEF, 69), p(Position.DEF, 71), p(Position.DEF, 72),
                p(Position.MID, 70), p(Position.MID, 71), p(Position.MID, 72), p(Position.MID, 73),
                p(Position.ATT, 74), p(Position.ATT, 75));
    }

    private static Map<UUID, Integer> fullFitness(List<Player> players) {
        return players.stream().collect(java.util.stream.Collectors.toMap(Player::getId, x -> 100));
    }

    private static Optional<SubDecision> decide(List<Player> onPitch, Set<UUID> hobbled,
                                                List<Player> bench, Map<UUID, Integer> fitness,
                                                int minute, Posture posture,
                                                boolean postureSubMade, int fatigueSubs, int totalSubs) {
        return AiSubstitutionBrain.decide(minute, posture, postureSubMade, fatigueSubs, totalSubs,
                onPitch, hobbled, bench, fitness);
    }

    // ---- injury rule ----

    @Test
    void aHobbledPlayerIsReplacedByTheBestSamePositionBenchPlayer() {
        List<Player> onPitch = pitch();
        Player hurt = onPitch.get(5);                      // MID 70
        Player weakMid = p(Position.MID, 60);
        Player strongMid = p(Position.MID, 66);
        Optional<SubDecision> d = decide(onPitch, Set.of(hurt.getId()),
                List.of(weakMid, strongMid), fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(hurt.getId(), strongMid.getId(), Kind.INJURY));
    }

    @Test
    void aHobbledKeeperIsOnlyReplacedByABenchKeeper() {
        List<Player> onPitch = pitch();
        Player gk = onPitch.get(0);
        // No bench GK -> no injury sub (and nothing else applies at minute 30, NORMAL).
        assertThat(decide(onPitch, Set.of(gk.getId()), List.of(p(Position.DEF, 80)),
                fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Bench GK -> replaced.
        Player benchGk = p(Position.GK, 60);
        assertThat(decide(onPitch, Set.of(gk.getId()), List.of(benchGk),
                fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0))
                .hasValue(new SubDecision(gk.getId(), benchGk.getId(), Kind.INJURY));
    }

    @Test
    void theFirstHobbledPlayerWithCoverWinsTheScan() {
        List<Player> onPitch = pitch();
        Player hurtDef = onPitch.get(1);                   // earlier in pitch order, no DEF on bench
        Player hurtAtt = onPitch.get(9);                   // later, but the only one with cover
        Player benchAtt = p(Position.ATT, 62);
        Optional<SubDecision> d = decide(onPitch, Set.of(hurtDef.getId(), hurtAtt.getId()),
                List.of(benchAtt), fullFitness(onPitch), 30, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(hurtAtt.getId(), benchAtt.getId(), Kind.INJURY));
    }

    @Test
    void injurySubsAreAllowedUpToTheFifthSubButNotBeyond() {
        List<Player> onPitch = pitch();
        Player hurt = onPitch.get(5);
        List<Player> bench = List.of(p(Position.MID, 60));
        assertThat(decide(onPitch, Set.of(hurt.getId()), bench, fullFitness(onPitch),
                30, Posture.NORMAL, false, 0, 4)).isPresent();     // 5th sub OK for injuries
        assertThat(decide(onPitch, Set.of(hurt.getId()), bench, fullFitness(onPitch),
                30, Posture.NORMAL, false, 0, 5)).isEmpty();       // no 6th sub ever
    }

    // ---- posture rule ----

    @Test
    void allOutBringsTheBestBenchAttackerOnForTheWorstDefender() {
        List<Player> onPitch = pitch();                    // 4 DEF (> 3), worst DEF overall 68
        Player worstDef = onPitch.get(1);
        Player att1 = p(Position.ATT, 64);
        Player att2 = p(Position.ATT, 67);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(att1, att2),
                fullFitness(onPitch), 75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(worstDef.getId(), att2.getId(), Kind.POSTURE));
    }

    @Test
    void parkTheBusBringsADefenderOnForTheWorstAttacker() {
        List<Player> onPitch = pitch();                    // 2 ATT (> 1), worst ATT overall 74
        Player worstAtt = onPitch.get(9);
        Player benchDef = p(Position.DEF, 63);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(benchDef),
                fullFitness(onPitch), 80, Posture.PARK_THE_BUS, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(worstAtt.getId(), benchDef.getId(), Kind.POSTURE));
    }

    @Test
    void postureSubsRespectTheFloorsTheOncePerMatchRuleAndTheHeldBackSub() {
        // Floor: only 3 DEF on pitch -> no ALL_OUT swap.
        List<Player> threeDefPitch = List.of(
                p(Position.GK, 70),
                p(Position.DEF, 68), p(Position.DEF, 69), p(Position.DEF, 71),
                p(Position.MID, 70), p(Position.MID, 71), p(Position.MID, 72),
                p(Position.MID, 73), p(Position.MID, 74),
                p(Position.ATT, 74), p(Position.ATT, 75));
        List<Player> bench = List.of(p(Position.ATT, 64));
        assertThat(decide(threeDefPitch, Set.of(), bench, fullFitness(threeDefPitch),
                75, Posture.ALL_OUT, false, 0, 0)).isEmpty();
        // Once per match: postureSubMade blocks a second one.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.ALL_OUT, true, 0, 1)).isEmpty();
        // Held-back sub: 4 subs already made -> no posture sub.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.ALL_OUT, false, 0, 4)).isEmpty();
        // Non-extreme postures never produce a posture sub.
        assertThat(decide(pitch(), Set.of(), bench, fullFitness(pitch()),
                75, Posture.PUSH, false, 0, 0)).isEmpty();
    }

    // ---- fatigue rule ----

    @Test
    void theMostTiredOutfielderIsFreshenedFromMinuteSixtyFive() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        Player tired = onPitch.get(6);                     // MID 71
        Player evenMoreTired = onPitch.get(7);             // MID 72 — lower fitness wins, not overall
        fitness.put(tired.getId(), 65);
        fitness.put(evenMoreTired.getId(), 55);
        Player benchMid = p(Position.MID, 61);
        Optional<SubDecision> d = decide(onPitch, Set.of(), List.of(benchMid), fitness,
                65, Posture.NORMAL, false, 0, 0);
        assertThat(d).hasValue(new SubDecision(evenMoreTired.getId(), benchMid.getId(), Kind.FATIGUE));
    }

    @Test
    void fatigueRespectsMinuteThresholdCapAndGoalkeeper() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        fitness.put(onPitch.get(6).getId(), 55);
        List<Player> bench = List.of(p(Position.MID, 61));
        // Minute 64: too early.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 64, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Fitness exactly 70 is NOT tired.
        Map<UUID, Integer> borderline = fullFitness(onPitch);
        borderline.put(onPitch.get(6).getId(), 70);
        assertThat(decide(onPitch, Set.of(), bench, borderline, 70, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Two fatigue subs already made: capped.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 70, Posture.NORMAL, false, 2, 2)).isEmpty();
        // A tired GK is never fatigue-subbed.
        Map<UUID, Integer> tiredGk = fullFitness(onPitch);
        tiredGk.put(onPitch.get(0).getId(), 40);
        assertThat(decide(onPitch, Set.of(), List.of(p(Position.GK, 60)), tiredGk,
                70, Posture.NORMAL, false, 0, 0)).isEmpty();
        // Held-back sub applies to fatigue too.
        assertThat(decide(onPitch, Set.of(), bench, fitness, 70, Posture.NORMAL, false, 0, 4)).isEmpty();
    }

    // ---- priority & degenerate cases ----

    @Test
    void injuryBeatsPostureBeatsFatigueInTheSameMinute() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        Player tired = onPitch.get(6);
        fitness.put(tired.getId(), 50);
        Player hurt = onPitch.get(5);
        List<Player> bench = List.of(p(Position.MID, 60), p(Position.ATT, 64));
        // Hobbled + ALL_OUT + tired, all at once: injury wins.
        Optional<SubDecision> d = decide(onPitch, Set.of(hurt.getId()), bench, fitness,
                75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d.orElseThrow().kind()).isEqualTo(Kind.INJURY);
        // Same but nobody hobbled: posture wins over fatigue.
        Optional<SubDecision> d2 = decide(onPitch, Set.of(), bench, fitness,
                75, Posture.ALL_OUT, false, 0, 0);
        assertThat(d2.orElseThrow().kind()).isEqualTo(Kind.POSTURE);
    }

    @Test
    void anEmptyBenchNeverProducesADecision() {
        List<Player> onPitch = pitch();
        Map<UUID, Integer> fitness = fullFitness(onPitch);
        fitness.put(onPitch.get(6).getId(), 40);
        assertThat(decide(onPitch, Set.of(onPitch.get(5).getId()), List.of(), fitness,
                80, Posture.ALL_OUT, false, 0, 0)).isEmpty();
    }
}
