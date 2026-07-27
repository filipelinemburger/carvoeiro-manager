package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static br.com.carvoeiromanager.player.DetailedPosition.*;
import static br.com.carvoeiromanager.player.RoleArchetype.*;
import static br.com.carvoeiromanager.player.RoleFocus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checked-in, independently-typed copy of the futbin.com/26/roles taxonomy (design spec §2.1),
 * asserted against {@link RoleArchetype}'s production MATRIX. {@link #TABLE} below was
 * transcribed row-by-row from the spec's markdown table (LD/LE, MD/ME and PD/PE rows expanded
 * into their own {@link DetailedPosition} entries, since {@code focusesFor} is position-specific)
 * — it does NOT read back the enum's own MATRIX, so drift on either side (a changed weight list,
 * a reordered focus, a dropped or added position) fails loudly instead of the guard silently
 * agreeing with whatever the production code says.
 */
class RoleArchetypeFutbinSyncTest {

    private record Expected(DetailedPosition position, RoleArchetype archetype, List<RoleFocus> focuses) {
    }

    private static final List<Expected> TABLE = List.of(
            // GOL
            new Expected(GOLEIRO, GOALKEEPER, List.of(DEFEND, BALANCED)),
            new Expected(GOLEIRO, SWEEPER_KEEPER, List.of(BALANCED, BUILD_UP)),
            new Expected(GOLEIRO, BALL_PLAYING_KEEPER, List.of(BUILD_UP)),

            // LD
            new Expected(LATERAL_DIREITO, FULLBACK, List.of(BALANCED, DEFEND, VERSATILE)),
            new Expected(LATERAL_DIREITO, WINGBACK, List.of(BALANCED, SUPPORT)),
            new Expected(LATERAL_DIREITO, FALSEBACK, List.of(BALANCED, DEFEND)),
            new Expected(LATERAL_DIREITO, ATTACKING_WINGBACK, List.of(SUPPORT, ATTACK)),
            new Expected(LATERAL_DIREITO, INVERTED_WINGBACK, List.of(BUILD_UP, ATTACK)),

            // LE
            new Expected(LATERAL_ESQUERDO, FULLBACK, List.of(BALANCED, DEFEND, VERSATILE)),
            new Expected(LATERAL_ESQUERDO, WINGBACK, List.of(BALANCED, SUPPORT)),
            new Expected(LATERAL_ESQUERDO, FALSEBACK, List.of(BALANCED, DEFEND)),
            new Expected(LATERAL_ESQUERDO, ATTACKING_WINGBACK, List.of(SUPPORT, ATTACK)),
            new Expected(LATERAL_ESQUERDO, INVERTED_WINGBACK, List.of(BUILD_UP, ATTACK)),

            // ZAG
            new Expected(ZAGUEIRO, DEFENDER, List.of(DEFEND, BALANCED)),
            new Expected(ZAGUEIRO, STOPPER, List.of(BALANCED, AGGRESSIVE)),
            new Expected(ZAGUEIRO, BALL_PLAYING_DEFENDER, List.of(DEFEND, BUILD_UP, AGGRESSIVE)),
            new Expected(ZAGUEIRO, WIDE_BACK, List.of(DEFEND, SUPPORT, AGGRESSIVE)),

            // VOL
            new Expected(VOLANTE, HOLDING, List.of(DEFEND, ROAMING, BALL_WINNING)),
            new Expected(VOLANTE, DEEP_LYING_PLAYMAKER, List.of(DEFEND, ROAMING, BUILD_UP)),
            new Expected(VOLANTE, CENTRE_HALF, List.of(DEFEND)),
            new Expected(VOLANTE, WIDE_HALF, List.of(DEFEND, BUILD_UP)),
            new Expected(VOLANTE, BOX_CRASHER, List.of(BALANCED)),

            // MC
            new Expected(MEIA_CENTRAL, BOX_TO_BOX, List.of(BALANCED, BALL_WINNING)),
            new Expected(MEIA_CENTRAL, HOLDING, List.of(DEFEND, BALL_WINNING)),
            new Expected(MEIA_CENTRAL, DEEP_LYING_PLAYMAKER, List.of(DEFEND, BUILD_UP)),
            new Expected(MEIA_CENTRAL, PLAYMAKER, List.of(ATTACK, ROAMING)),
            new Expected(MEIA_CENTRAL, HALF_WINGER, List.of(BALANCED, ATTACK, SUPPORT)),

            // MEI
            new Expected(MEIA_ARMADOR, PLAYMAKER, List.of(BALANCED, ROAMING, BUILD_UP)),
            new Expected(MEIA_ARMADOR, HALF_WINGER, List.of(BALANCED, ATTACK, ROAMING)),
            new Expected(MEIA_ARMADOR, SHADOW_STRIKER, List.of(ATTACK)),
            new Expected(MEIA_ARMADOR, CLASSIC_10, List.of(ATTACK, WIDE, VERSATILE)),

            // MD
            new Expected(MEIA_DIREITA, WINGER, List.of(BALANCED, ATTACK)),
            new Expected(MEIA_DIREITA, WIDE_PLAYMAKER, List.of(ATTACK, BUILD_UP)),
            new Expected(MEIA_DIREITA, WIDE_MIDFIELDER, List.of(SUPPORT, DEFEND, BUILD_UP)),
            new Expected(MEIA_DIREITA, INSIDE_FORWARD, List.of(BALANCED, ATTACK)),

            // ME
            new Expected(MEIA_ESQUERDA, WINGER, List.of(BALANCED, ATTACK)),
            new Expected(MEIA_ESQUERDA, WIDE_PLAYMAKER, List.of(ATTACK, BUILD_UP)),
            new Expected(MEIA_ESQUERDA, WIDE_MIDFIELDER, List.of(SUPPORT, DEFEND, BUILD_UP)),
            new Expected(MEIA_ESQUERDA, INSIDE_FORWARD, List.of(BALANCED, ATTACK)),

            // PD
            new Expected(PONTA_DIREITA, WINGER, List.of(BALANCED, ATTACK, VERSATILE)),
            new Expected(PONTA_DIREITA, WIDE_PLAYMAKER, List.of(ATTACK, BUILD_UP)),
            new Expected(PONTA_DIREITA, INSIDE_FORWARD, List.of(BALANCED, ATTACK, ROAMING)),

            // PE
            new Expected(PONTA_ESQUERDA, WINGER, List.of(BALANCED, ATTACK, VERSATILE)),
            new Expected(PONTA_ESQUERDA, WIDE_PLAYMAKER, List.of(ATTACK, BUILD_UP)),
            new Expected(PONTA_ESQUERDA, INSIDE_FORWARD, List.of(BALANCED, ATTACK, ROAMING)),

            // ATA
            new Expected(ATACANTE, ADVANCED_FORWARD, List.of(ATTACK, VERSATILE, SUPPORT)),
            new Expected(ATACANTE, TARGET_FORWARD, List.of(ATTACK, BALANCED, WIDE)),
            new Expected(ATACANTE, FALSE_9, List.of(BUILD_UP, ATTACK)),
            new Expected(ATACANTE, POACHER, List.of(ATTACK, SUPPORT, VERSATILE))
    );

    @Test
    void everyExpectedRowIsValidWithFocusesInOrder() {
        for (Expected row : TABLE) {
            assertThat(row.archetype().validFor(row.position()))
                    .as("%s should be valid for %s", row.archetype(), row.position())
                    .isTrue();
            assertThat(row.archetype().focusesFor(row.position()))
                    .as("focusesFor(%s) on %s (order-sensitive, default first)", row.position(), row.archetype())
                    .containsExactlyElementsOf(row.focuses());
        }
    }

    @Test
    void archetypesHaveExactlyTheDocumentedPositionsNoMoreNoLess() {
        Map<RoleArchetype, List<DetailedPosition>> expectedPositions = TABLE.stream()
                .collect(Collectors.groupingBy(Expected::archetype,
                        Collectors.mapping(Expected::position, Collectors.toList())));

        for (RoleArchetype archetype : RoleArchetype.values()) {
            assertThat(archetype.validPositions())
                    .as("validPositions() for %s", archetype)
                    .containsExactlyInAnyOrderElementsOf(expectedPositions.getOrDefault(archetype, List.of()));
        }
    }

    @Test
    void everyPositionHasAtLeastThreeArchetypes() {
        for (DetailedPosition position : DetailedPosition.values()) {
            long count = Arrays.stream(RoleArchetype.values()).filter(a -> a.validFor(position)).count();
            assertThat(count).as("archetype count for %s", position).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void defaultArchetypeIsAlwaysValidForItsOwnPosition() {
        for (DetailedPosition position : DetailedPosition.values()) {
            RoleArchetype def = RoleArchetype.defaultFor(position);
            assertThat(def.validFor(position)).as("defaultFor(%s) = %s should be valid for it", position, def)
                    .isTrue();
        }
    }
}
