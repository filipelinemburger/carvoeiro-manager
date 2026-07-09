package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SetPiecesTest {

    /** A stub RNG that returns a fixed sequence of doubles — lets us hit exact roll bands. */
    private static Random fixed(double value) {
        return new Random() {
            @Override public double nextDouble() { return value; }
        };
    }

    private static Player player(Position pos, Attribute attr, int value) {
        Attributes attributes = Attributes.builder().set(attr, value).build();
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T-" + pos, pos, 25, "BRA",
                attributes, 70);
    }

    // ---- share(): same scale as the old single-threat formula, clamped
    @Test
    void shareSpansNineToTwentySevenPercent() {
        assertThat(SetPieces.share(0.0, 0.0)).isEqualTo(0.09, within(1e-9));   // 0.18 * 0.5
        assertThat(SetPieces.share(1.0, 1.0)).isEqualTo(0.27, within(1e-9));   // 0.18 * 1.5
        assertThat(SetPieces.share(1.0, 0.0)).isEqualTo(0.18, within(1e-9));   // blended 0.5
    }

    // ---- roll(): weights CORNER 50*(0.5+c), FREE_KICK 30*(0.5+f), PENALTY 20.
    // With threats (0,0): corner=25, freeKick=15, penalty=20, total=60.
    @Test
    void rollPicksByWeightBands() {
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.2))).isEqualTo(SetPieces.Kind.CORNER);      // 12 < 25
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.5))).isEqualTo(SetPieces.Kind.FREE_KICK);   // 30 in [25,40)
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.9))).isEqualTo(SetPieces.Kind.PENALTY);     // 54 >= 40
    }

    @Test
    void aSkilledCornerTakerShiftsTheMixTowardCorners() {
        // threats (1,0): corner=75, freeKick=15, penalty=20, total=110 — 0.6*110=66 < 75 stays CORNER,
        // whereas with (0,0) the same 0.6 draw (=36) lands in FREE_KICK territory... (36 in [25,40)).
        assertThat(SetPieces.roll(1.0, 0.0, fixed(0.6))).isEqualTo(SetPieces.Kind.CORNER);
        assertThat(SetPieces.roll(0.0, 0.0, fixed(0.6))).isEqualTo(SetPieces.Kind.FREE_KICK);
    }

    // ---- conversionChance(): [0.70, 0.95]
    @Test
    void conversionChanceBounds() {
        assertThat(SetPieces.conversionChance(0)).isEqualTo(0.70, within(1e-9));
        assertThat(SetPieces.conversionChance(99)).isEqualTo(0.95, within(1e-9));
    }

    // ---- headerWeight(): position factor x (Cabeceio + 10); keepers never
    @Test
    void headerWeightFavorsAttackersAndDefendersAndExcludesKeepers() {
        assertThat(SetPieces.headerWeight(player(Position.ATT, Attribute.CABECEIO, 50))).isEqualTo(600);
        assertThat(SetPieces.headerWeight(player(Position.DEF, Attribute.CABECEIO, 50))).isEqualTo(480);
        assertThat(SetPieces.headerWeight(player(Position.MID, Attribute.CABECEIO, 50))).isEqualTo(240);
        assertThat(SetPieces.headerWeight(player(Position.GK, Attribute.CABECEIO, 99))).isZero();
    }

    // ---- takerOrFallback()
    @Test
    void designatedTakerWinsWhenInThePool() {
        Player taker = player(Position.MID, Attribute.BOLA_PARADA, 10);
        Player better = player(Position.ATT, Attribute.BOLA_PARADA, 90);
        Player picked = SetPieces.takerOrFallback(List.of(better, taker), taker.getId(),
                p -> p.attribute(Attribute.BOLA_PARADA));
        assertThat(picked).isSameAs(taker);
    }

    @Test
    void missingTakerFallsBackToFirstMaxByAttribute() {
        Player a = player(Position.MID, Attribute.BOLA_PARADA, 60);
        Player b = player(Position.ATT, Attribute.BOLA_PARADA, 90);
        Player c = player(Position.DEF, Attribute.BOLA_PARADA, 90);   // tied with b — b is first
        Player picked = SetPieces.takerOrFallback(List.of(a, b, c), UUID.randomUUID(),
                p -> p.attribute(Attribute.BOLA_PARADA));
        assertThat(picked).isSameAs(b);
        assertThat(SetPieces.takerOrFallback(List.of(a, b, c), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(b);
    }

    @Test
    void fallbackSkipsGoalkeepersUnlessPoolIsAllKeepers() {
        Player gk = player(Position.GK, Attribute.BOLA_PARADA, 99);
        Player mid = player(Position.MID, Attribute.BOLA_PARADA, 40);
        assertThat(SetPieces.takerOrFallback(List.of(gk, mid), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(mid);
        assertThat(SetPieces.takerOrFallback(List.of(gk), null,
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(gk);
    }

    @Test
    void aDesignatedKeeperTakerIsHonored() {
        Player gk = player(Position.GK, Attribute.BOLA_PARADA, 99);
        Player mid = player(Position.MID, Attribute.BOLA_PARADA, 40);
        assertThat(SetPieces.takerOrFallback(List.of(gk, mid), gk.getId(),
                p -> p.attribute(Attribute.BOLA_PARADA))).isSameAs(gk);
    }
}
