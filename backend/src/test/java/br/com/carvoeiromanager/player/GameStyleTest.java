package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit coverage for {@link GameStyle}: the squad-fit formula, the EQUILIBRADO byte-identical
 * no-op guarantee, and the attack/defense multiplier arithmetic for an attacking and a defensive
 * style. Players are built as real (non-persisted) {@link Player} entities via the full-attribute
 * factory, so {@code p.attribute(...)} returns the raw values the fit formula reads.
 */
class GameStyleTest {

    /** A non-GK player whose given raw attributes are set (rest default 0). */
    private static Player outfield(Map<Attribute, Integer> attrs) {
        Attributes.Builder builder = Attributes.builder();
        attrs.forEach(builder::set);
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 25, "BRA",
                builder.build(), 70);
    }

    /**
     * Eleven outfield players whose JOGO_DE_PASSES fit attributes (PASSE/VISAO/COMPOSTURA) are all
     * set to {@code v}. Since the weights sum to 1.0, each player's weighted score is exactly {@code v},
     * so the squad average — and thus the pre-clamp fit input — is {@code v}.
     */
    private static List<Player> passStyleXi(int v) {
        return IntStream.range(0, 11)
                .mapToObj(i -> outfield(Map.of(
                        Attribute.PASSE, v, Attribute.VISAO, v, Attribute.COMPOSTURA, v)))
                .toList();
    }

    // ---- (a) fit formula ------------------------------------------------------------------------

    @Test
    void fitMapsAverageFiftyEightToZero() {
        assertThat(GameStyle.JOGO_DE_PASSES.fit(passStyleXi(58))).isEqualTo(0.0, within(1e-9));
    }

    @Test
    void fitMapsAverageSeventyEightToPlusOne() {
        assertThat(GameStyle.JOGO_DE_PASSES.fit(passStyleXi(78))).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void fitMapsAverageThirtyEightToMinusOne() {
        assertThat(GameStyle.JOGO_DE_PASSES.fit(passStyleXi(38))).isEqualTo(-1.0, within(1e-9));
    }

    @Test
    void fitClampsBeyondTheSpanInBothDirections() {
        assertThat(GameStyle.JOGO_DE_PASSES.fit(passStyleXi(99))).isEqualTo(1.0, within(1e-9));
        assertThat(GameStyle.JOGO_DE_PASSES.fit(passStyleXi(0))).isEqualTo(-1.0, within(1e-9));
    }

    @Test
    void weightedFitUsesTheDeclaredAttributeWeights() {
        // PASSE .5, VISAO .3, COMPOSTURA .2 -> weighted score 0.5*98 + 0.3*58 + 0.2*18 = 70.0,
        // so fit = (70 - 58) / 20 = 0.6, proving the weights (not a plain average) drive the score.
        List<Player> xi = IntStream.range(0, 11)
                .mapToObj(i -> outfield(Map.of(
                        Attribute.PASSE, 98, Attribute.VISAO, 58, Attribute.COMPOSTURA, 18)))
                .toList();
        assertThat(GameStyle.JOGO_DE_PASSES.fit(xi)).isEqualTo(0.6, within(1e-9));
    }

    @Test
    void negativeFitReducesAttackAndDefenseMultipliersBelowTheBase() {
        double fit = GameStyle.JOGO_DE_PASSES.fit(passStyleXi(38)); // -1
        assertThat(GameStyle.JOGO_DE_PASSES.attackMult(fit))
                .isLessThan(GameStyle.JOGO_DE_PASSES.attackMult(0.0));
        double defFit = GameStyle.RETRANCA.fit(List.of()); // 0 for defensive style with empty XI
        assertThat(GameStyle.RETRANCA.defenseMult(-1.0))
                .isLessThan(GameStyle.RETRANCA.defenseMult(defFit));
    }

    // ---- (b) EQUILIBRADO is the byte-identical no-op --------------------------------------------

    @Test
    void equilibradoIsAByteIdenticalNoOp() {
        // Determinism doctrine: the neutral style multiplies attack/defense/decay by exactly 1.0 and
        // contributes zero fit for ANY XI, so a squad on EQUILIBRADO is bit-for-bit unchanged from the
        // pre-GameStyle engine. These exact-equality assertions lock that guarantee.
        for (double fit : new double[] {-1.0, -0.37, 0.0, 0.42, 1.0, 5.0}) {
            assertThat(GameStyle.EQUILIBRADO.attackMult(fit)).isEqualTo(1.0);
            assertThat(GameStyle.EQUILIBRADO.defenseMult(fit)).isEqualTo(1.0);
        }
        assertThat(GameStyle.EQUILIBRADO.decayMult()).isEqualTo(1.0);

        // fit() returns exactly 0.0 for any XI, including an empty list (never divides by an empty
        // weight set, never NaN).
        assertThat(GameStyle.EQUILIBRADO.fit(List.of())).isEqualTo(0.0);
        assertThat(GameStyle.EQUILIBRADO.fit(passStyleXi(99))).isEqualTo(0.0);
        assertThat(GameStyle.EQUILIBRADO.fit(passStyleXi(0))).isEqualTo(0.0);
    }

    @Test
    void everyStyleReturnsZeroFitForAnEmptyOutfield() {
        for (GameStyle style : GameStyle.values()) {
            assertThat(style.fit(List.of())).as("%s empty-XI fit", style).isEqualTo(0.0);
            assertThat(style.fit(null)).as("%s null-XI fit", style).isEqualTo(0.0);
        }
    }

    // ---- (c) multiplier arithmetic for an attacking and a defensive style -----------------------

    @Test
    void attackingStyleArithmeticAtFitExtremes() {
        // JOGO_DE_PASSES: baseAttack 1.03, attackFitScale 0.05, defenseFitScale 0.0.
        assertThat(GameStyle.JOGO_DE_PASSES.attackMult(1.0)).isEqualTo(1.08, within(1e-9));
        assertThat(GameStyle.JOGO_DE_PASSES.attackMult(-1.0)).isEqualTo(0.98, within(1e-9));
        // Defense fit-scale is 0, so defense is flat at its base regardless of fit.
        assertThat(GameStyle.JOGO_DE_PASSES.defenseMult(1.0)).isEqualTo(0.99, within(1e-9));
        assertThat(GameStyle.JOGO_DE_PASSES.defenseMult(-1.0)).isEqualTo(0.99, within(1e-9));
        assertThat(GameStyle.JOGO_DE_PASSES.decayMult()).isEqualTo(1.0);
    }

    @Test
    void defensiveStyleArithmeticAtFitExtremes() {
        // RETRANCA: baseDefense 1.05, defenseFitScale 0.05, attackFitScale 0.0, baseAttack 0.94.
        assertThat(GameStyle.RETRANCA.defenseMult(1.0)).isEqualTo(1.10, within(1e-9));
        assertThat(GameStyle.RETRANCA.defenseMult(-1.0)).isEqualTo(1.00, within(1e-9));
        assertThat(GameStyle.RETRANCA.attackMult(1.0)).isEqualTo(0.94, within(1e-9));
        assertThat(GameStyle.RETRANCA.attackMult(-1.0)).isEqualTo(0.94, within(1e-9));
    }

    @Test
    void pressaoAltaCarriesTheRaisedDecayMultiplier() {
        // The one style with a non-neutral decay: PRESSAO_ALTA tires the squad faster (1.15).
        assertThat(GameStyle.PRESSAO_ALTA.decayMult()).isEqualTo(1.15, within(1e-9));
    }

    @Test
    void labelsAreThePtBrDisplayStrings() {
        assertThat(GameStyle.EQUILIBRADO.label()).isEqualTo("Equilibrado");
        assertThat(GameStyle.CONTRA_ATAQUE.label()).isEqualTo("Contra-ataque");
        assertThat(GameStyle.PRESSAO_ALTA.label()).isEqualTo("Pressão alta");
    }
}
