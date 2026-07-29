package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers familiar roles (design spec §2.3): {@link Player#familiarRoles()},
 * {@link Player#familiarity(RoleArchetype)}, {@link Player#setFamiliarRoles(List)} parsing/
 * validation, and {@link RoleArchetype#defaultFamiliarFor} generation-time draws.
 */
class PlayerFamiliarRolesTest {

    private Player player(Position position) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Teste", position,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
    }

    @Test
    void constructedPlayersAlwaysHaveOneFullyFamiliarRoleValidForTheirPosition() {
        Position[] coarse = Position.values();
        for (int i = 0; i < 500; i++) {
            Player p = player(coarse[i % coarse.length]);

            Map<RoleArchetype, Integer> familiar = p.familiarRoles();
            List<RoleArchetype> fullyFamiliar = familiar.entrySet().stream()
                    .filter(e -> e.getValue() == 2)
                    .map(Map.Entry::getKey)
                    .toList();

            assertThat(fullyFamiliar).as("player %s (%s)", p.getId(), p.getDetailedPosition())
                    .isNotEmpty();
            for (RoleArchetype archetype : familiar.keySet()) {
                assertThat(p.positions()).as("archetype %s valid for one of %s's positions",
                                archetype, p.positions())
                        .anyMatch(archetype::validFor);
            }
        }
    }

    @Test
    void aboutAQuarterGetASecondSomewhatFamiliarRole() {
        Position[] coarse = Position.values();
        int total = 8000;
        int withSecond = 0;
        for (int i = 0; i < total; i++) {
            Player p = player(coarse[i % coarse.length]);
            if (p.familiarRoles().containsValue(1)) {
                withSecond++;
            }
        }

        double share = (double) withSecond / total;
        assertThat(share).as("share of players with a second, somewhat-familiar role")
                .isBetween(0.18, 0.32);
    }

    @Test
    void highPassingMidfieldersLeanPlaymakerish() {
        Attributes highPasse = Attributes.builder().set(Attribute.PASSE, 75).build();
        List<String> withHighPasse = RoleArchetype.defaultFamiliarFor(
                DetailedPosition.MEIA_CENTRAL, highPasse, new Random(1));
        assertThat(withHighPasse.get(0)).isEqualTo("DEEP_LYING_PLAYMAKER++");

        Attributes midPasse = Attributes.builder().set(Attribute.PASSE, 50).build();
        List<String> withMidPasse = RoleArchetype.defaultFamiliarFor(
                DetailedPosition.MEIA_CENTRAL, midPasse, new Random(1));
        assertThat(withMidPasse.get(0)).isEqualTo("BOX_TO_BOX++");
    }

    @Test
    void familiarityParsesAndRoundTrips() {
        Player p = player(Position.DEF);

        p.setFamiliarRoles(List.of("WINGBACK++", "FULLBACK+"));

        assertThat(p.familiarity(RoleArchetype.WINGBACK)).isEqualTo(2);
        assertThat(p.familiarity(RoleArchetype.FULLBACK)).isEqualTo(1);
        assertThat(p.familiarity(RoleArchetype.POACHER)).isEqualTo(0);

        assertThatThrownBy(() -> p.setFamiliarRoles(List.of("NOT_A_ROLE++")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setFamiliarRoles(List.of("WINGBACK")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyMutatorDropsTheDecodeCacheSoLaterReadsSeeTheNewValue() {
        // familiarRoles()/positions() memoize their CSV parse for the match engine's hot loops.
        // Reading BEFORE each mutation is the whole point: it primes the cache, so a mutator that
        // forgot to invalidate would keep serving the stale answer here.
        Player p = player(Position.DEF);

        p.setFamiliarRoles(List.of("WINGBACK++"));
        assertThat(p.familiarRoles()).containsExactly(Map.entry(RoleArchetype.WINGBACK, 2));
        p.setFamiliarRoles(List.of("STOPPER++", "DEFENDER+"));
        assertThat(p.familiarRoles())
                .containsExactly(Map.entry(RoleArchetype.STOPPER, 2), Map.entry(RoleArchetype.DEFENDER, 1));
        assertThat(p.familiarity(RoleArchetype.WINGBACK)).isZero();

        p.setPositions(DetailedPosition.ZAGUEIRO, List.of(DetailedPosition.VOLANTE));
        assertThat(p.positions()).containsExactly(DetailedPosition.ZAGUEIRO, DetailedPosition.VOLANTE);

        p.setPositions(DetailedPosition.LATERAL_ESQUERDO, List.of());
        assertThat(p.positions()).containsExactly(DetailedPosition.LATERAL_ESQUERDO);

        p.setDetailedPosition(DetailedPosition.PONTA_ESQUERDA);
        assertThat(p.positions()).containsExactly(DetailedPosition.PONTA_ESQUERDA);
    }
}
