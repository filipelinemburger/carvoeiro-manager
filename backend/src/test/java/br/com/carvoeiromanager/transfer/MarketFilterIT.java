package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E: the market's filter/sort/page behavior. Every test scopes its assertions to a
 * single, fully-controlled AI club (wiped and repopulated) so the day-one listing noise from
 * {@code SaveGameService.createCareer} (SURPLUS floor etc.) never bleeds into the expectations.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MarketFilterIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("mkt_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Empties one AI club's squad so the caller can populate it with a known, controlled set. */
    private UUID aWipedAiClub(Career c) {
        UUID otherClubId = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        playerRepository.deleteAll(playerRepository.findByClubId(otherClubId));
        return otherClubId;
    }

    private Player listedPlayer(Career c, UUID clubId, String name, Position position, int age, int overall) {
        Player player = Player.create(c.id(), clubId, name, position, age, "Brasil",
                60, 60, 60, 60, 10, 60, overall);
        player.listForTransfer(ListedReason.SURPLUS);
        playerRepository.save(player);
        return player;
    }

    @Test
    void filteringByClubIdReturnsOnlyThatClubsListedPlayers() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Player A1", Position.MID, 25, 70);
        listedPlayer(c, clubA, "Player A2", Position.MID, 25, 65);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(), new MarketFilter(clubA, null, null, null, null, null, null, null), 1, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).allMatch(m -> m.clubId().equals(clubA));
    }

    @Test
    void filteringByPositionReturnsOnlyThatPosition() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Striker", Position.ATT, 25, 70);
        listedPlayer(c, clubA, "Defender", Position.DEF, 25, 70);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(), new MarketFilter(clubA, Position.ATT, null, null, null, null, null, null), 1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).name()).isEqualTo("Striker");
    }

    @Test
    void filteringByAgeRangeExcludesOutOfRangeAges() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Young", Position.MID, 19, 70);
        listedPlayer(c, clubA, "Prime", Position.MID, 27, 70);
        listedPlayer(c, clubA, "Veteran", Position.MID, 35, 70);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(), new MarketFilter(clubA, null, 25, 30, null, null, null, null), 1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).name()).isEqualTo("Prime");
    }

    @Test
    void filteringByOverallRangeExcludesOutOfRangeOveralls() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Weak", Position.MID, 25, 50);
        listedPlayer(c, clubA, "Solid", Position.MID, 25, 75);
        listedPlayer(c, clubA, "Star", Position.MID, 25, 92);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(), new MarketFilter(clubA, null, null, null, 70, 80, null, null), 1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).name()).isEqualTo("Solid");
    }

    @Test
    void filteringByValueRangeExcludesOutOfRangeValues() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        Player cheap = listedPlayer(c, clubA, "Cheap", Position.MID, 25, 50);
        Player expensive = listedPlayer(c, clubA, "Expensive", Position.MID, 25, 90);
        BigDecimal cheapValue = transferValuation.marketValue(cheap.getOverall(), cheap.getAge());
        BigDecimal expensiveValue = transferValuation.marketValue(expensive.getOverall(), expensive.getAge());
        assertThat(expensiveValue).isGreaterThan(cheapValue);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(),
                new MarketFilter(clubA, null, null, null, null, null,
                        cheapValue.add(BigDecimal.ONE), null),
                1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).name()).isEqualTo("Expensive");
    }

    @Test
    void combinedFiltersIntersectRatherThanUnion() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Matches Both", Position.ATT, 24, 80);
        listedPlayer(c, clubA, "Wrong Position", Position.DEF, 24, 80);
        listedPlayer(c, clubA, "Wrong Age", Position.ATT, 33, 80);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(
                c.id(), c.userId(),
                new MarketFilter(clubA, Position.ATT, 20, 28, null, null, null, null), 1, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).name()).isEqualTo("Matches Both");
    }

    @Test
    void pageAndSizeAreClampedRatherThanRejected() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        for (int i = 0; i < 5; i++) {
            listedPlayer(c, clubA, "Player " + i, Position.MID, 25, 60 + i);
        }
        MarketFilter onlyThisClub = new MarketFilter(clubA, null, null, null, null, null, null, null);

        PageResponse<MarketPlayerResponse> zeroSize = transferService.getMarket(c.id(), c.userId(), onlyThisClub, 1, 0);
        assertThat(zeroSize.size()).isEqualTo(1);
        assertThat(zeroSize.content()).hasSize(1);

        PageResponse<MarketPlayerResponse> hugeSize = transferService.getMarket(c.id(), c.userId(), onlyThisClub, 1, 500);
        assertThat(hugeSize.size()).isEqualTo(100);
        assertThat(hugeSize.content()).hasSize(5);

        PageResponse<MarketPlayerResponse> zeroPage = transferService.getMarket(c.id(), c.userId(), onlyThisClub, 0, 5);
        assertThat(zeroPage.page()).isEqualTo(1);
        assertThat(zeroPage.content()).hasSize(5);
    }

    @Test
    void outOfRangePageReturnsEmptyContentButKeepsTotals() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        listedPlayer(c, clubA, "Only One", Position.MID, 25, 70);
        MarketFilter onlyThisClub = new MarketFilter(clubA, null, null, null, null, null, null, null);

        PageResponse<MarketPlayerResponse> page = transferService.getMarket(c.id(), c.userId(), onlyThisClub, 10, 5);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void pagingIsStableAndSortedByValueDescendingAcrossPages() {
        Career c = createCareer();
        UUID clubA = aWipedAiClub(c);
        // Strictly increasing overall -> strictly increasing (distinct) value; no ties to worry about.
        List<Player> players = List.of(
                listedPlayer(c, clubA, "P60", Position.MID, 25, 60),
                listedPlayer(c, clubA, "P65", Position.MID, 25, 65),
                listedPlayer(c, clubA, "P70", Position.MID, 25, 70),
                listedPlayer(c, clubA, "P75", Position.MID, 25, 75),
                listedPlayer(c, clubA, "P80", Position.MID, 25, 80));
        MarketFilter onlyThisClub = new MarketFilter(clubA, null, null, null, null, null, null, null);

        List<UUID> seenAcrossPages = new java.util.ArrayList<>();
        int totalPages = transferService.getMarket(c.id(), c.userId(), onlyThisClub, 1, 2).totalPages();
        assertThat(totalPages).isEqualTo(3); // 5 items, size 2 -> pages of 2, 2, 1

        for (int page = 1; page <= totalPages; page++) {
            PageResponse<MarketPlayerResponse> result = transferService.getMarket(c.id(), c.userId(), onlyThisClub, page, 2);
            seenAcrossPages.addAll(result.content().stream().map(MarketPlayerResponse::playerId).toList());
        }

        // Every player appears exactly once across all pages, no duplicates and none skipped.
        assertThat(seenAcrossPages).hasSize(5);
        assertThat(seenAcrossPages).containsExactlyInAnyOrderElementsOf(players.stream().map(Player::getId).toList());
        // Highest overall (P80, highest value) must be first: value descending.
        assertThat(seenAcrossPages.get(0)).isEqualTo(players.get(4).getId());
        assertThat(seenAcrossPages.get(seenAcrossPages.size() - 1)).isEqualTo(players.get(0).getId());
    }
}
