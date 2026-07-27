package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit proof of every {@link PlayerInstruction} weight/multiplier effect in BOTH match engines and
 * the strength calculator. The load-bearing determinism guarantee: a {@code null} instruction (the
 * always-present case for AI clubs and unset players) leaves every weight/multiplier exactly at its
 * pre-instruction value, so an empty instructions map is byte-identical to the pre-change engine.
 */
class PlayerInstructionWeightTest {

    private static Player player(Position pos, int finishing, int passe, int visao, int cruzamento,
                                 int cabeceio, int chuteLonge, int desarme, int agressividade) {
        Attributes attrs = Attributes.builder()
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passe)
                .set(Attribute.VISAO, visao)
                .set(Attribute.CRUZAMENTO, cruzamento)
                .set(Attribute.CABECEIO, cabeceio)
                .set(Attribute.CHUTE_LONGE, chuteLonge)
                .set(Attribute.DESARME, desarme)
                .set(Attribute.AGRESSIVIDADE, agressividade)
                .build();
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA", attrs, 70);
    }

    // ---- scorer weight ----

    @Test
    void infiltrarNaAreaScalesTheScorerWeightByOneAndAHalfInBothEngines() {
        Player att = player(Position.ATT, 50, 0, 0, 0, 0, 0, 0, 0); // goalWeight = 12*(50+10) = 720
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.MISTO, null)).isEqualTo(720);
        // ×1.5 via integer *3/2.
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.MISTO, PlayerInstruction.INFILTRAR_NA_AREA))
                .isEqualTo(720 * 3 / 2);
        assertThat(FixtureSimulator.scorerWeight(att, AttackZone.MISTO, PlayerInstruction.INFILTRAR_NA_AREA))
                .isEqualTo(1080);
        // The zone kicker is included BEFORE the ×1.5: LATERAIS adds CABECEIO/2.
        Player wide = player(Position.ATT, 50, 0, 0, 0, 41, 0, 0, 0); // 720 + 41/2 = 740
        assertThat(LiveMatchEngine.scorerWeight(wide, AttackZone.LATERAIS, PlayerInstruction.INFILTRAR_NA_AREA))
                .isEqualTo(740 * 3 / 2);
    }

    @Test
    void chutarDeLongeRaisesAMidfieldersPositionFactorToEightThenScalesByOneAndAQuarter() {
        Player mid = player(Position.MID, 50, 0, 0, 0, 0, 0, 0, 0);
        // No instruction: MID position-factor 6 → 6*(50+10) = 360.
        assertThat(LiveMatchEngine.scorerWeight(mid, AttackZone.MISTO, null)).isEqualTo(360);
        // CHUTAR_DE_LONGE: factor 8 → 8*60 = 480, then ×1.25 → 600.
        assertThat(LiveMatchEngine.scorerWeight(mid, AttackZone.MISTO, PlayerInstruction.CHUTAR_DE_LONGE))
                .isEqualTo(480 * 5 / 4);
        assertThat(FixtureSimulator.scorerWeight(mid, AttackZone.MISTO, PlayerInstruction.CHUTAR_DE_LONGE))
                .isEqualTo(600);
        // CENTRO adds CHUTE_LONGE/2 to the factor-8 base before ×1.25: (480 + 15) * 5 / 4.
        Player central = player(Position.MID, 50, 0, 0, 0, 0, 30, 0, 0);
        assertThat(LiveMatchEngine.scorerWeight(central, AttackZone.CENTRO, PlayerInstruction.CHUTAR_DE_LONGE))
                .isEqualTo((480 + 15) * 5 / 4);
        assertThat(FixtureSimulator.scorerWeight(central, AttackZone.CENTRO, PlayerInstruction.CHUTAR_DE_LONGE))
                .isEqualTo((480 + 15) * 5 / 4);
    }

    @Test
    void chutarDeLongeDoesNotChangeTheAttackerPositionFactor() {
        Player att = player(Position.ATT, 50, 0, 0, 0, 0, 0, 0, 0); // stays factor 12 → 720
        // Only the ×1.25 applies for an attacker (factor unchanged): 720 * 5 / 4 = 900.
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.MISTO, PlayerInstruction.CHUTAR_DE_LONGE))
                .isEqualTo(720 * 5 / 4);
    }

    // ---- assist weight ----

    @Test
    void cruzarMaisScalesTheAssistWeightByOneAndAHalfInBothEngines() {
        Player p = player(Position.MID, 0, 55, 45, 35, 0, 0, 0, 0);
        int mistoBase = 10 + 55 + 45 + 35; // 145
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.MISTO, RoleArchetype.BOX_TO_BOX, null))
                .isEqualTo(mistoBase);
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.MISTO, RoleArchetype.BOX_TO_BOX,
                PlayerInstruction.CRUZAR_MAIS)).isEqualTo(mistoBase * 3 / 2);
        assertThat(FixtureSimulator.assistWeight(p, AttackZone.MISTO, RoleArchetype.BOX_TO_BOX,
                PlayerInstruction.CRUZAR_MAIS)).isEqualTo(145 * 3 / 2);
        // Compounds with the LATERAIS wide-role boost: (10 + 2*35)=80, WINGER ×3/2=120, then ×3/2=180.
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.LATERAIS, RoleArchetype.WINGER,
                PlayerInstruction.CRUZAR_MAIS)).isEqualTo(80 * 3 / 2 * 3 / 2);
    }

    // ---- card weight ----

    @Test
    void marcacaoDuraScalesTheCardWeightByOneAndAHalfInBothEngines() {
        Player p = player(Position.DEF, 0, 0, 0, 0, 0, 0, 10, 0); // tackling 10 → base 11
        assertThat(LiveMatchEngine.cardWeight(p, null)).isEqualTo(11);
        assertThat(FixtureSimulator.cardWeight(p, null)).isEqualTo(11);
        assertThat(LiveMatchEngine.cardWeight(p, PlayerInstruction.MARCACAO_DURA)).isEqualTo(11 * 3 / 2);
        assertThat(FixtureSimulator.cardWeight(p, PlayerInstruction.MARCACAO_DURA)).isEqualTo(16);
    }

    @Test
    void cardWeightWithNullInstructionEqualsTacklingPlusOneAcrossTacklingValues() {
        for (int tackling = 0; tackling <= 20; tackling++) {
            Player p = player(Position.DEF, 0, 0, 0, 0, 0, 0, tackling, 0);
            assertThat(LiveMatchEngine.cardWeight(p, null)).isEqualTo(tackling + 1);
            assertThat(FixtureSimulator.cardWeight(p, null)).isEqualTo(tackling + 1);
        }
    }

    // ---- foul weight ----

    @Test
    void marcacaoDuraScalesTheFoulWeightByOneAndAHalfInBothEngines() {
        Player p = player(Position.DEF, 0, 0, 0, 0, 0, 0, 30, 40); // AGR 40 + DES 30 + 10 = 80
        assertThat(LiveMatchEngine.foulWeight(p, null)).isEqualTo(80);
        assertThat(FixtureSimulator.foulWeight(p, null)).isEqualTo(80);
        assertThat(LiveMatchEngine.foulWeight(p, PlayerInstruction.MARCACAO_DURA)).isEqualTo(80 * 3 / 2);
        assertThat(FixtureSimulator.foulWeight(p, PlayerInstruction.MARCACAO_DURA)).isEqualTo(120);
    }

    // ---- strength contributions ----

    @Test
    void segurarPosicaoLowersAttackAndRaisesDefense() {
        List<Player> xi = standardXi();
        TeamMatchInput base = calc().compute(xi, profile(xi, Map.of()));
        TeamMatchInput held = calc().compute(xi, profile(xi, allInstructed(xi, PlayerInstruction.SEGURAR_POSICAO)));
        // attack ×0.85 (GK contributes 0 attack, so the whole outfield sum scales), defense ×1.10.
        assertThat(held.attack()).isCloseTo(base.attack() * 0.85, within(1e-9));
        assertThat(held.defense()).isCloseTo(base.defense() * 1.10, within(1e-9));
    }

    @Test
    void marcacaoDuraRaisesDefenseByEightPercentAndLeavesAttackUntouched() {
        List<Player> xi = standardXi();
        TeamMatchInput base = calc().compute(xi, profile(xi, Map.of()));
        TeamMatchInput hard = calc().compute(xi, profile(xi, allInstructed(xi, PlayerInstruction.MARCACAO_DURA)));
        assertThat(hard.attack()).isCloseTo(base.attack(), within(1e-9));
        assertThat(hard.defense()).isCloseTo(base.defense() * 1.08, within(1e-9));
    }

    @Test
    void emptyInstructionsMapLeavesStrengthByteIdentical() {
        List<Player> xi = standardXi();
        TeamMatchInput withEmpty = calc().compute(xi, profile(xi, Map.of()));
        TeamMatchInput withNoneInstructed = calc().compute(xi,
                profile(xi, allInstructed(xi, PlayerInstruction.DRIBLAR_MAIS)));
        // DRIBLAR_MAIS is inert, so it is identical to no instruction at all.
        assertThat(withNoneInstructed.attack()).isEqualTo(withEmpty.attack());
        assertThat(withNoneInstructed.defense()).isEqualTo(withEmpty.defense());
    }

    // ---- DRIBLAR_MAIS is inert ----

    @Test
    void driblarMaisChangesNoWeightOrMultiplier() {
        Player att = player(Position.ATT, 50, 55, 45, 35, 40, 30, 20, 30);
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.MISTO, PlayerInstruction.DRIBLAR_MAIS))
                .isEqualTo(LiveMatchEngine.scorerWeight(att, AttackZone.MISTO, null));
        assertThat(FixtureSimulator.scorerWeight(att, AttackZone.CENTRO, PlayerInstruction.DRIBLAR_MAIS))
                .isEqualTo(FixtureSimulator.scorerWeight(att, AttackZone.CENTRO, null));
        assertThat(LiveMatchEngine.assistWeight(att, AttackZone.LATERAIS, RoleArchetype.WINGER,
                PlayerInstruction.DRIBLAR_MAIS))
                .isEqualTo(LiveMatchEngine.assistWeight(att, AttackZone.LATERAIS, RoleArchetype.WINGER, null));
        assertThat(LiveMatchEngine.cardWeight(att, PlayerInstruction.DRIBLAR_MAIS))
                .isEqualTo(LiveMatchEngine.cardWeight(att, null));
        assertThat(FixtureSimulator.foulWeight(att, PlayerInstruction.DRIBLAR_MAIS))
                .isEqualTo(FixtureSimulator.foulWeight(att, null));
    }

    // ---- labels ----

    @Test
    void labelsAreTheExpectedPtBrStrings() {
        assertThat(PlayerInstruction.INFILTRAR_NA_AREA.label()).isEqualTo("Infiltrar na área");
        assertThat(PlayerInstruction.CHUTAR_DE_LONGE.label()).isEqualTo("Chutar de longe");
        assertThat(PlayerInstruction.CRUZAR_MAIS.label()).isEqualTo("Cruzar mais");
        assertThat(PlayerInstruction.SEGURAR_POSICAO.label()).isEqualTo("Segurar posição");
        assertThat(PlayerInstruction.MARCACAO_DURA.label()).isEqualTo("Marcação dura");
        assertThat(PlayerInstruction.DRIBLAR_MAIS.label()).isEqualTo("Driblar mais");
    }

    // ---- helpers ----

    private static TeamStrengthCalculator calc() {
        return new TeamStrengthCalculator();
    }

    private static Player rated(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    private static List<Player> standardXi() {
        return List.of(
                rated(Position.GK, 70),
                rated(Position.DEF, 70), rated(Position.DEF, 70), rated(Position.DEF, 70), rated(Position.DEF, 70),
                rated(Position.MID, 70), rated(Position.MID, 70), rated(Position.MID, 70), rated(Position.MID, 70),
                rated(Position.ATT, 70), rated(Position.ATT, 70));
    }

    private static TacticalProfile profile(List<Player> xi, Map<UUID, PlayerInstruction> instructions) {
        Map<UUID, RoleAssignment> roles = LegacyRoles.forXi(xi);
        return new TacticalProfile(Mentality.BALANCED, GameStyle.EQUILIBRADO, AttackZone.MISTO, "4-4-2",
                Pressing.MEDIUM, Marking.ZONAL, roles, null, null, null, instructions, Map.of());
    }

    /**
     * Assigns the instruction to EVERY player (incl. the GK) so the attack/defense ratio isolates
     * cleanly. TeamStrengthCalculator applies the instructions map blindly; a GK never carries one in
     * production (LineupService rejects it), but the calculator does not care — this is math isolation.
     */
    private static Map<UUID, PlayerInstruction> allInstructed(List<Player> xi, PlayerInstruction instr) {
        Map<UUID, PlayerInstruction> m = new HashMap<>();
        xi.forEach(p -> m.put(p.getId(), instr));
        return m;
    }
}
