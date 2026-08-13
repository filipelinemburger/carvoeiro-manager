package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the researched real-player data (Task 6) against drift from the {@link DetailedPosition}
 * / {@link RoleArchetype} enums: every one of the 1509 seeded players (1343 club + 166 free agent)
 * must carry researched {@code positions}/{@code roles} that are internally consistent and valid
 * per the live enums — not a hand-copied snapshot of them.
 */
class SeedResearchValidationTest {

    private final SeedLoader loader = new SeedLoader();

    /** Every club player and every free agent, paired with a label for failure messages. */
    private List<LabeledPlayer> allPlayers() {
        SeedCareer career = loader.loadCareer();
        Stream<LabeledPlayer> clubPlayers = career.clubPool().stream()
                .flatMap(club -> club.players().stream()
                        .map(p -> new LabeledPlayer(club.shortName() + " / " + p.name(), p)));
        Stream<LabeledPlayer> freeAgents = loader.loadFreeAgents().stream()
                .map(p -> new LabeledPlayer("FA / " + p.name(), p));
        return Stream.concat(clubPlayers, freeAgents).toList();
    }

    private record LabeledPlayer(String label, SeedPlayer player) {}

    @Test
    void clubPoolHasSixtyClubsWithThirteenHundredFortyThreePlayersTotal() {
        SeedCareer career = loader.loadCareer();

        // 55 + 5 (Série C batch 2: Guarani, Floresta, Confiança, Ypiranga and Maringá, the
        // mid-table clubs retained from Série C 2025) = 60 clubs; 1238 + 105 = 1343 players.
        assertThat(career.clubPool()).hasSize(60);
        int total = career.clubPool().stream().mapToInt(c -> c.players().size()).sum();
        assertThat(total).isEqualTo(1343);
    }

    @Test
    void freeAgentPoolHasOneHundredSixtySixPlayers() {
        // 179 - 12 - 1: the Série A intake put twelve of them on a real club roster, and the
        // Série C intake one more (Rafael Tavares, Amazonas' number 10). A player cannot be
        // both employed and on the free-agent market.
        assertThat(loader.loadFreeAgents()).hasSize(166);
    }

    @Test
    void everyPlayerHasResearchedPositionsWithNoSilentGaps() {
        assertThat(allPlayers()).allSatisfy(lp ->
                assertThat(lp.player().positions())
                        .as("%s positions", lp.label())
                        .isNotNull()
                        .hasSizeBetween(1, 3));
    }

    @Test
    void everyPlayerHasResearchedRolesWithNoSilentGaps() {
        assertThat(allPlayers()).allSatisfy(lp ->
                assertThat(lp.player().roles())
                        .as("%s roles", lp.label())
                        .isNotNull()
                        .hasSizeBetween(1, 2));
    }

    @Test
    void primaryPositionCoarseBandMatchesTheSeedsPositionField() {
        assertThat(allPlayers()).allSatisfy(lp -> {
            Position coarse = Position.valueOf(lp.player().position());
            DetailedPosition primary = DetailedPosition.valueOf(lp.player().positions().get(0));
            assertThat(primary.coarse())
                    .as("%s primary position coarse band", lp.label())
                    .isEqualTo(coarse);
        });
    }

    @Test
    void noPlayerListsDuplicatePositions() {
        assertThat(allPlayers()).allSatisfy(lp -> {
            List<String> positions = lp.player().positions();
            assertThat(Set.copyOf(positions))
                    .as("%s duplicate positions in %s", lp.label(), positions)
                    .hasSize(positions.size());
        });
    }

    @Test
    void goalkeepersListExactlyGoleiroAndNoOutfieldPlayerListsGoleiro() {
        assertThat(allPlayers()).allSatisfy(lp -> {
            boolean isGk = "GK".equals(lp.player().position());
            if (isGk) {
                assertThat(lp.player().positions())
                        .as("%s (GK) positions", lp.label())
                        .containsExactly("GOLEIRO");
            } else {
                assertThat(lp.player().positions())
                        .as("%s (outfield) positions", lp.label())
                        .doesNotContain("GOLEIRO");
            }
        });
    }

    @Test
    void everyRoleTokenParsesWithTheFirstTokenAlwaysDoublePlus() {
        assertThat(allPlayers()).allSatisfy(lp -> {
            List<String> roles = lp.player().roles();
            assertThat(roles.get(0))
                    .as("%s first role token must be ++", lp.label())
                    .endsWith("++");
            for (String token : roles) {
                assertThat(token).as("%s role token %s", lp.label(), token).matches(".+\\+{1,2}$");
                String archetypeName = token.endsWith("++")
                        ? token.substring(0, token.length() - 2)
                        : token.substring(0, token.length() - 1);
                // Throws if the archetype name doesn't exist — the real enum, not a copy of it.
                assertThat(RoleArchetype.valueOf(archetypeName)).isNotNull();
            }
        });
    }

    @Test
    void everyRoleArchetypeIsValidForAtLeastOneOfThePlayersPositions() {
        assertThat(allPlayers()).allSatisfy(lp -> {
            List<DetailedPosition> positions = lp.player().positions().stream()
                    .map(DetailedPosition::valueOf).toList();
            for (String token : lp.player().roles()) {
                String archetypeName = token.replaceAll("\\+", "");
                RoleArchetype archetype = RoleArchetype.valueOf(archetypeName);
                assertThat(positions.stream().anyMatch(archetype::validFor))
                        .as("%s role %s must be validFor one of %s", lp.label(), token, positions)
                        .isTrue();
            }
        });
    }
}
