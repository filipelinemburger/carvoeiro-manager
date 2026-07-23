package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link DetailedPosition#defaultFor}: coarse-consistency for every branch, the position
 * distributions, and the PASSE-gated FALSO_9 rule.
 */
class DetailedPositionTest {

    @Test
    void everyResultIsCoarseConsistentWithTheRequestedPosition() {
        for (Position position : Position.values()) {
            for (int passe = 0; passe <= 99; passe += 7) {
                Random rng = new Random(passe * 31L + position.ordinal());
                for (int i = 0; i < 200; i++) {
                    DetailedPosition d = DetailedPosition.defaultFor(position, passe, rng);
                    assertThat(d.coarse())
                            .as("defaultFor(%s, %d) -> %s must map back to %s", position, passe, d, position)
                            .isEqualTo(position);
                }
            }
        }
    }

    @Test
    void goalkeepersAlwaysGetGoleiro() {
        Random rng = new Random(1);
        for (int i = 0; i < 100; i++) {
            assertThat(DetailedPosition.defaultFor(Position.GK, i % 100, rng))
                    .isEqualTo(DetailedPosition.GOLEIRO);
        }
    }

    @Test
    void defendersOnlyYieldTheThreeDefValuesAndProduceEachAcrossManySeeds() {
        Set<DetailedPosition> seen = EnumSet.noneOf(DetailedPosition.class);
        for (int seed = 0; seed < 500; seed++) {
            DetailedPosition d = DetailedPosition.defaultFor(Position.DEF, 50, new Random(seed));
            assertThat(d).isIn(DetailedPosition.ZAGUEIRO,
                    DetailedPosition.LATERAL_ESQUERDO, DetailedPosition.LATERAL_DIREITO);
            seen.add(d);
        }
        assertThat(seen).containsExactlyInAnyOrder(DetailedPosition.ZAGUEIRO,
                DetailedPosition.LATERAL_ESQUERDO, DetailedPosition.LATERAL_DIREITO);
    }

    @Test
    void midfieldersOnlyYieldVolanteOrMeiaArmador() {
        Set<DetailedPosition> seen = EnumSet.noneOf(DetailedPosition.class);
        Random rng = new Random(11);
        for (int i = 0; i < 200; i++) {
            DetailedPosition d = DetailedPosition.defaultFor(Position.MID, 50, rng);
            assertThat(d).isIn(DetailedPosition.VOLANTE, DetailedPosition.MEIA_ARMADOR);
            seen.add(d);
        }
        assertThat(seen).containsExactlyInAnyOrder(DetailedPosition.VOLANTE, DetailedPosition.MEIA_ARMADOR);
    }

    @Test
    void attackersNeverGetFalso9BelowThePasseThreshold() {
        for (int seed = 0; seed < 2000; seed++) {
            DetailedPosition d = DetailedPosition.defaultFor(Position.ATT, 59, new Random(seed));
            assertThat(d).isNotEqualTo(DetailedPosition.FALSO_9);
            assertThat(d).isIn(DetailedPosition.PONTA, DetailedPosition.ATACANTE);
        }
    }

    @Test
    void attackersCanGetFalso9AtOrAboveThePasseThreshold() {
        Set<DetailedPosition> seen = EnumSet.noneOf(DetailedPosition.class);
        for (int seed = 0; seed < 2000; seed++) {
            seen.add(DetailedPosition.defaultFor(Position.ATT, 60, new Random(seed)));
        }
        assertThat(seen).contains(DetailedPosition.FALSO_9,
                DetailedPosition.PONTA, DetailedPosition.ATACANTE);
    }

    @Test
    void labelsAndShortLabelsAreDistinctAndNonBlank() {
        for (DetailedPosition d : DetailedPosition.values()) {
            assertThat(d.label()).isNotBlank();
            assertThat(d.shortLabel()).isNotBlank();
            assertThat(d.coarse()).isNotNull();
        }
    }
}
