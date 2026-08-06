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
 * / {@link RoleArchetype} enums: every one of the 863 seeded players (684 club + 179 free agent)
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
    void clubPoolHasThirtyOneClubsWithSixHundredEightyFourPlayersTotal() {
        SeedCareer career = loader.loadCareer();

        // 28 + 3 (JOI/CAR/SCA, the real 12-club 2026 Catarinense field) = 31 clubs;
        // 625 + 59 (21 + 20 + 18 new players) = 684 players.
        assertThat(career.clubPool()).hasSize(31);
        int total = career.clubPool().stream().mapToInt(c -> c.players().size()).sum();
        assertThat(total).isEqualTo(684);
    }

    @Test
    void freeAgentPoolHasOneHundredSeventyNinePlayers() {
        assertThat(loader.loadFreeAgents()).hasSize(179);
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
