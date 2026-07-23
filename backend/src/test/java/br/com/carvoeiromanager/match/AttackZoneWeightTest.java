package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level proof of the AttackZone scorer/assist weights for BOTH match engines. The critical
 * guarantee: on the neutral {@link AttackZone#MISTO} the scorer weight equals the pre-AttackZone
 * {@code goalWeight} and the assist weight equals the pre-AttackZone composite
 * {@code PASSE + VISAO + CRUZAMENTO + 10}, so a MISTO squad's open-play attribution is byte-identical
 * to the pre-change engine. The two engines are also cross-checked so they round the ×1.5 boost and
 * the integer half-attribute identically.
 */
class AttackZoneWeightTest {

    private static Player player(Position pos, int finishing, int passe, int visao,
                                 int cruzamento, int cabeceio, int chuteLonge) {
        Attributes attrs = Attributes.builder()
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passe)
                .set(Attribute.VISAO, visao)
                .set(Attribute.CRUZAMENTO, cruzamento)
                .set(Attribute.CABECEIO, cabeceio)
                .set(Attribute.CHUTE_LONGE, chuteLonge)
                .build();
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA", attrs, 70);
    }

    /** The pre-AttackZone scorer weight: positionFactor(ATT12/MID6/DEF2/GK0) * (finishing + 10). */
    private static int legacyGoalWeight(Player p) {
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 12;
            case MID -> 6;
            case DEF -> 2;
            case GK -> 0;
        };
        return positionFactor * (p.getFinishing() + 10);
    }

    /** The pre-AttackZone assist weight: PASSE + VISAO + CRUZAMENTO + 10. */
    private static int legacyAssistWeight(Player p) {
        return p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                + p.attribute(Attribute.CRUZAMENTO) + 10;
    }

    @Test
    void mistoScorerWeightEqualsTheLegacyGoalWeightForBothEngines() {
        for (Position pos : Position.values()) {
            Player p = player(pos, 50, 55, 45, 35, 40, 30);
            int legacy = legacyGoalWeight(p);
            assertThat(LiveMatchEngine.scorerWeight(p, AttackZone.MISTO, null)).isEqualTo(legacy);
            assertThat(FixtureSimulator.scorerWeight(p, AttackZone.MISTO, null)).isEqualTo(legacy);
        }
    }

    @Test
    void mistoAssistWeightEqualsTheLegacyCompositeAndIsRoleIndependent() {
        Player p = player(Position.ATT, 50, 55, 45, 35, 40, 30);
        int legacy = legacyAssistWeight(p);
        // 55 + 45 + 35 + 10 = 145 — role must not matter for MISTO.
        assertThat(legacy).isEqualTo(145);
        for (PlayerRole role : PlayerRole.values()) {
            assertThat(LiveMatchEngine.assistWeight(p, AttackZone.MISTO, role, null)).isEqualTo(legacy);
            assertThat(FixtureSimulator.assistWeight(p, AttackZone.MISTO, role, null)).isEqualTo(legacy);
        }
    }

    @Test
    void lateraisScorerAddsHalfHeadingCentroAddsHalfLongShots() {
        Player att = player(Position.ATT, 50, 55, 45, 35, 41, 31); // odd attrs check integer /2 rounding
        int base = legacyGoalWeight(att); // 12 * 60 = 720
        assertThat(base).isEqualTo(720);
        // +41/2 = +20 (integer), +31/2 = +15 (integer)
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.LATERAIS, null)).isEqualTo(720 + 20);
        assertThat(LiveMatchEngine.scorerWeight(att, AttackZone.CENTRO, null)).isEqualTo(720 + 15);
        assertThat(FixtureSimulator.scorerWeight(att, AttackZone.LATERAIS, null)).isEqualTo(720 + 20);
        assertThat(FixtureSimulator.scorerWeight(att, AttackZone.CENTRO, null)).isEqualTo(720 + 15);
    }

    @Test
    void lateraisAssistLeansOnCrossingAndBoostsWideRoles() {
        Player p = player(Position.ATT, 50, 55, 45, 35, 40, 30);
        int base = 10 + 2 * 35; // 80
        assertThat(base).isEqualTo(80);
        // Non-wide role: no boost.
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.LATERAIS, PlayerRole.POACHER, null)).isEqualTo(80);
        // Wide roles: ×1.5 via integer w*3/2.
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.LATERAIS, PlayerRole.WINGER, null)).isEqualTo(80 * 3 / 2);
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.LATERAIS, PlayerRole.FULL_BACK, null)).isEqualTo(120);
        // Both engines agree.
        assertThat(FixtureSimulator.assistWeight(p, AttackZone.LATERAIS, PlayerRole.WINGER, null)).isEqualTo(120);
    }

    @Test
    void centroAssistLeansOnPassingVisionAndBoostsCentralCreators() {
        Player p = player(Position.MID, 50, 55, 45, 35, 40, 30);
        int base = 10 + 55 + 45; // 110
        assertThat(base).isEqualTo(110);
        // Non-central role: no boost.
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.CENTRO, PlayerRole.WINGER, null)).isEqualTo(110);
        // Central creators: ×1.5.
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.CENTRO, PlayerRole.PLAYMAKER, null)).isEqualTo(110 * 3 / 2);
        assertThat(LiveMatchEngine.assistWeight(p, AttackZone.CENTRO, PlayerRole.BOX_TO_BOX, null)).isEqualTo(165);
        assertThat(FixtureSimulator.assistWeight(p, AttackZone.CENTRO, PlayerRole.PLAYMAKER, null)).isEqualTo(165);
    }

    @Test
    void labelsAreTheExpectedPtBrStrings() {
        assertThat(AttackZone.LATERAIS.label()).isEqualTo("Pelos lados");
        assertThat(AttackZone.CENTRO.label()).isEqualTo("Pelo centro");
        assertThat(AttackZone.MISTO.label()).isEqualTo("Misto");
    }
}
