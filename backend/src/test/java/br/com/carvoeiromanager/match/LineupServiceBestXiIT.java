package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for the slot-aware {@link LineupService#bestXi} against a real seeded squad:
 * the two invariants the match engine relies on (a full XI with exactly one keeper) plus the
 * determinism guarantee that both per-fixture calls (pre-sim + attribution) select the same XI.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupServiceBestXiIT {

    @Autowired
    LineupService lineupService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID anyClubId() {
        User user = User.create("bestxi_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.managedClubId();
    }

    @Test
    void bestXiReturnsElevenWithExactlyOneKeeper() {
        UUID clubId = anyClubId();

        List<Player> xi = lineupService.bestXi(clubId);

        assertThat(xi).hasSize(11);
        assertThat(xi.stream().filter(p -> p.getPosition() == Position.GK)).hasSize(1);
        assertThat(xi.get(0).getPosition()).as("GK is first").isEqualTo(Position.GK);
        // No duplicates.
        assertThat(xi.stream().map(Player::getId).distinct().count()).isEqualTo(11L);
    }

    @Test
    void bestXiIsDeterministicAcrossRepeatedCalls() {
        UUID clubId = anyClubId();

        List<UUID> first = lineupService.bestXi(clubId).stream().map(Player::getId).toList();
        List<UUID> second = lineupService.bestXi(clubId).stream().map(Player::getId).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void bestBenchExcludesEveryXiMember() {
        UUID clubId = anyClubId();

        List<Player> xi = lineupService.bestXi(clubId);
        List<Player> bench = lineupService.bestBench(clubId, xi);

        List<UUID> xiIds = xi.stream().map(Player::getId).toList();
        assertThat(bench).isNotEmpty();
        assertThat(bench.stream().map(Player::getId)).doesNotContainAnyElementsOf(xiIds);
        assertThat(bench).allMatch(Player::isEligible);
    }
}
