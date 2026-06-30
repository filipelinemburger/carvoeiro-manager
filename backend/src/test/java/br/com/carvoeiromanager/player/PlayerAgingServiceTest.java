package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerAgingServiceTest {

    private final PlayerAgingService aging = new PlayerAgingService(new OverallCalculator());

    private Player player(Position position, int age, int attr) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", position,
                age, "Brasil", attr, attr, attr, attr, attr, attr, attr);
    }

    @Test
    void youthNeverLoseAttributesAndMayImprove() {
        // Given a 20-year-old with mid attributes
        Player p = player(Position.MID, 20, 50);

        // When the age curve is applied
        aging.applyAgeCurve(p, new Random(7));

        // Then every attribute is in [50, 53] (deltas 0..3) and overall did not drop
        assertThat(p.getPace()).isBetween(50, 53);
        assertThat(p.getFinishing()).isBetween(50, 53);
        assertThat(p.getPassing()).isBetween(50, 53);
        assertThat(p.getTackling()).isBetween(50, 53);
        assertThat(p.getGoalkeeping()).isBetween(50, 53);
        assertThat(p.getStamina()).isBetween(50, 53);
        assertThat(p.getOverall()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void oldPlayersDeclineAndPaceStaminaFallFastest() {
        // Given a 35-year-old with attributes 70
        Player p = player(Position.MID, 35, 70);

        // When the age curve is applied (deltas -4..-1; pace/stamina get an extra -1)
        aging.applyAgeCurve(p, new Random(7));

        // Then non-pace/stamina attributes are in [66, 69]; pace and stamina in [65, 68]
        assertThat(p.getFinishing()).isBetween(66, 69);
        assertThat(p.getPassing()).isBetween(66, 69);
        assertThat(p.getTackling()).isBetween(66, 69);
        assertThat(p.getGoalkeeping()).isBetween(66, 69);
        assertThat(p.getPace()).isBetween(65, 68);
        assertThat(p.getStamina()).isBetween(65, 68);
        assertThat(p.getOverall()).isLessThan(70);
    }

    @Test
    void attributesAreClampedToTheNinetyNineCeiling() {
        // Given a youth maxed at 99
        Player p = player(Position.ATT, 19, 99);

        // When the (positive) youth curve is applied
        aging.applyAgeCurve(p, new Random(3));

        // Then attributes never exceed 99
        assertThat(p.getPace()).isEqualTo(99);
        assertThat(p.getFinishing()).isEqualTo(99);
        assertThat(p.getStamina()).isEqualTo(99);
    }

    @Test
    void attributesAreClampedToTheFloorOfOne() {
        // Given a very old player at the floor
        Player p = player(Position.DEF, 36, 1);

        // When the (negative) decline curve is applied
        aging.applyAgeCurve(p, new Random(3));

        // Then attributes never drop below 1
        assertThat(p.getPace()).isEqualTo(1);
        assertThat(p.getTackling()).isEqualTo(1);
        assertThat(p.getStamina()).isEqualTo(1);
    }

    @Test
    void theCurveIsDeterministicForAGivenSeed() {
        // Given two identical players
        Player a = player(Position.MID, 26, 60);
        Player b = player(Position.MID, 26, 60);

        // When evolved with the same seed
        aging.applyAgeCurve(a, new Random(123));
        aging.applyAgeCurve(b, new Random(123));

        // Then they end up identical
        assertThat(b.getPace()).isEqualTo(a.getPace());
        assertThat(b.getFinishing()).isEqualTo(a.getFinishing());
        assertThat(b.getOverall()).isEqualTo(a.getOverall());
    }

    @Test
    void retirementProbabilityRisesWithAge() {
        assertThat(aging.retirementProbability(30)).isEqualTo(0.0);
        assertThat(aging.retirementProbability(34)).isEqualTo(0.0);
        assertThat(aging.retirementProbability(35)).isEqualTo(0.25);
        assertThat(aging.retirementProbability(36)).isEqualTo(0.45);
        assertThat(aging.retirementProbability(37)).isEqualTo(0.70);
        assertThat(aging.retirementProbability(38)).isEqualTo(1.0);
        assertThat(aging.retirementProbability(41)).isEqualTo(1.0);
    }
}
