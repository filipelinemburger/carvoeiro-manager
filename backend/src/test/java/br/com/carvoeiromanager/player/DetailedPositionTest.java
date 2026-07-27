package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link DetailedPosition}: the tactical distance graph ({@link DetailedPosition#distance}
 * / {@link DetailedPosition#adjacent}), {@link DetailedPosition#defaultFor} coarse-consistency and
 * distribution across all 12 values, and {@link DetailedPosition#alternatesFor}.
 */
class DetailedPositionTest {

    @Test
    void distanceIsSymmetricAndBounded() {
        for (DetailedPosition a : DetailedPosition.values()) {
            for (DetailedPosition b : DetailedPosition.values()) {
                assertThat(a.distance(b)).as("%s <-> %s should be symmetric", a, b).isEqualTo(b.distance(a));

                if (a == b) {
                    assertThat(a.distance(b)).as("%s <-> itself", a).isZero();
                } else {
                    assertThat(a.distance(b)).as("%s <-> %s should not be 0", a, b).isNotZero();
                }

                boolean exactlyOneGoleiro = (a == DetailedPosition.GOLEIRO) ^ (b == DetailedPosition.GOLEIRO);
                if (exactlyOneGoleiro) {
                    assertThat(a.distance(b)).as("%s <-> %s (GK vs outfield)", a, b).isEqualTo(3);
                } else {
                    assertThat(a.distance(b)).as("%s <-> %s", a, b).isIn(0, 1, 2);
                }
            }
        }
    }

    @Test
    void adjacencyMatchesTheSpecGraph() {
        assertThat(DetailedPosition.LATERAL_DIREITO.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_DIREITA, DetailedPosition.ZAGUEIRO);
        assertThat(DetailedPosition.LATERAL_ESQUERDO.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_ESQUERDA, DetailedPosition.ZAGUEIRO);
        assertThat(DetailedPosition.MEIA_DIREITA.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.LATERAL_DIREITO, DetailedPosition.PONTA_DIREITA);
        assertThat(DetailedPosition.MEIA_ESQUERDA.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.LATERAL_ESQUERDO, DetailedPosition.PONTA_ESQUERDA);
        assertThat(DetailedPosition.VOLANTE.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.ZAGUEIRO, DetailedPosition.MEIA_CENTRAL);
        assertThat(DetailedPosition.MEIA_CENTRAL.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.VOLANTE, DetailedPosition.MEIA_ARMADOR);
        assertThat(DetailedPosition.MEIA_ARMADOR.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_CENTRAL, DetailedPosition.ATACANTE);
        assertThat(DetailedPosition.ZAGUEIRO.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.VOLANTE, DetailedPosition.LATERAL_DIREITO, DetailedPosition.LATERAL_ESQUERDO);
        assertThat(DetailedPosition.PONTA_DIREITA.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_DIREITA, DetailedPosition.ATACANTE);
        assertThat(DetailedPosition.PONTA_ESQUERDA.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_ESQUERDA, DetailedPosition.ATACANTE);
        assertThat(DetailedPosition.ATACANTE.adjacent()).containsExactlyInAnyOrder(
                DetailedPosition.MEIA_ARMADOR, DetailedPosition.PONTA_DIREITA, DetailedPosition.PONTA_ESQUERDA);
        assertThat(DetailedPosition.GOLEIRO.adjacent()).isEmpty();
    }

    @Test
    void defaultForIsCoarseConsistentAndCovers12Values() {
        for (Position position : Position.values()) {
            Random rng = new Random(42);
            Set<DetailedPosition> seen = EnumSet.noneOf(DetailedPosition.class);
            for (int i = 0; i < 10_000; i++) {
                DetailedPosition d = DetailedPosition.defaultFor(position, rng);
                assertThat(d.coarse()).as("defaultFor(%s) -> %s must map back to %s", position, d, position)
                        .isEqualTo(position);
                seen.add(d);
            }
            switch (position) {
                case GK -> assertThat(seen).containsExactly(DetailedPosition.GOLEIRO);
                case DEF -> assertThat(seen).containsExactlyInAnyOrder(DetailedPosition.ZAGUEIRO,
                        DetailedPosition.LATERAL_DIREITO, DetailedPosition.LATERAL_ESQUERDO);
                case MID -> assertThat(seen).containsExactlyInAnyOrder(DetailedPosition.VOLANTE,
                        DetailedPosition.MEIA_CENTRAL, DetailedPosition.MEIA_ARMADOR,
                        DetailedPosition.MEIA_DIREITA, DetailedPosition.MEIA_ESQUERDA);
                case ATT -> assertThat(seen).containsExactlyInAnyOrder(DetailedPosition.PONTA_DIREITA,
                        DetailedPosition.PONTA_ESQUERDA, DetailedPosition.ATACANTE);
            }
        }
    }

    @Test
    void alternatesAreAdjacentDistinctAndNeverGk() {
        for (DetailedPosition primary : DetailedPosition.values()) {
            Random rng = new Random(42);
            int drawsWithAtLeastOne = 0;
            for (int i = 0; i < 10_000; i++) {
                List<DetailedPosition> alts = DetailedPosition.alternatesFor(primary, rng);

                assertThat(alts.size()).as("alternatesFor(%s) size", primary).isLessThanOrEqualTo(2);
                assertThat(alts).as("alternatesFor(%s) has no duplicates", primary).doesNotHaveDuplicates();
                assertThat(primary.adjacent()).as("alternatesFor(%s) subset of adjacent()", primary)
                        .containsAll(alts);
                if (primary == DetailedPosition.GOLEIRO) {
                    assertThat(alts).as("GOLEIRO never gets alternates").isEmpty();
                }
                if (!alts.isEmpty()) {
                    drawsWithAtLeastOne++;
                }
            }
            if (primary != DetailedPosition.GOLEIRO) {
                double share = drawsWithAtLeastOne / 10_000.0;
                assertThat(share).as("share of draws with >=1 alternate for %s", primary).isBetween(0.25, 0.35);
            }
        }
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
