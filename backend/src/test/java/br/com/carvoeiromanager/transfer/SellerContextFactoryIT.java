package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.HEALTHY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SellerContextFactory} builds each flag correctly against a real (fully
 * controlled) squad: key-player rank threshold, position-cover margin, and delegation to
 * {@link ClubFinancialHealthEvaluator} for financial health. The {@code listed} flag is a
 * pass-through of {@code Player#isTransferListed} and isn't re-tested here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SellerContextFactoryIT {

    @Autowired SellerContextFactory sellerContextFactory;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID managedClubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("scf_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Club anAiClub(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> !club.getId().equals(c.managedClubId())).findFirst().orElseThrow();
    }

    /** Wipes the club's current (non-youth) squad and replaces it with exactly these players. */
    private void replaceSquad(Career c, Club club, List<Player> players) {
        playerRepository.deleteAll(playerRepository.findByClubId(club.getId()));
        players.forEach(p -> assertThat(p.getSaveGameId()).isEqualTo(c.id())); // sanity: same save
        playerRepository.saveAll(players);
    }

    private Player player(Career c, Club club, Position position, int overall) {
        return Player.create(c.id(), club.getId(), "P-" + UUID.randomUUID(), position,
                25, "Brasil", 60, 60, 60, 60, 60, 60, overall);
    }

    private void makeDesperate(Club club) {
        club.setBalance(BigDecimal.ZERO);
        clubRepository.save(club);
    }

    private void makeHealthy(Club club) {
        club.setBalance(new BigDecimal("999999999.00"));
        clubRepository.save(club);
    }

    @Test
    void rankElevenOrBetterIsAKeyPlayerAndRankTwelveIsNot() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);

        List<Player> squad = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            squad.add(player(c, club, Position.MID, 90 - i)); // overalls 90..79, 12 distinct ranks
        }
        replaceSquad(c, club, squad);
        Player rankEleven = squad.get(10); // overall 80 -> rank 11
        Player rankTwelve = squad.get(11); // overall 79 -> rank 12

        assertThat(sellerContextFactory.build(rankEleven, club).keyPlayer()).isTrue();
        assertThat(sellerContextFactory.build(rankTwelve, club).keyPlayer()).isFalse();
    }

    @Test
    void tiedOverallsBreakByAscendingId() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);

        // 10 players strictly above, then 2 players tied at the boundary overall: the lower id
        // takes rank 11 (key player), the higher id takes rank 12 (not).
        List<Player> squad = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            squad.add(player(c, club, Position.MID, 90 - i)); // ranks 1..10
        }
        Player tiedA = player(c, club, Position.MID, 70);
        Player tiedB = player(c, club, Position.MID, 70);
        Player lowerId = tiedA.getId().compareTo(tiedB.getId()) <= 0 ? tiedA : tiedB;
        Player higherId = lowerId == tiedA ? tiedB : tiedA;
        squad.add(tiedA);
        squad.add(tiedB);
        replaceSquad(c, club, squad);

        assertThat(sellerContextFactory.build(lowerId, club).keyPlayer()).isTrue();
        assertThat(sellerContextFactory.build(higherId, club).keyPlayer()).isFalse();
    }

    @Test
    void samePositionCoverWithinThreeOverallMeansNoCoverIsFalse() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);
        Player target = player(c, club, Position.DEF, 80);
        Player cover = player(c, club, Position.DEF, 77); // exactly overall - 3: still cover
        replaceSquad(c, club, List.of(target, cover));

        assertThat(sellerContextFactory.build(target, club).noCover()).isFalse();
    }

    @Test
    void samePositionCoverJustBelowThresholdMeansNoCoverIsTrue() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);
        Player target = player(c, club, Position.DEF, 80);
        Player almostCover = player(c, club, Position.DEF, 76); // overall - 4: not cover
        replaceSquad(c, club, List.of(target, almostCover));

        assertThat(sellerContextFactory.build(target, club).noCover()).isTrue();
    }

    @Test
    void aStrongOtherPositionPlayerDoesNotCountAsCover() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);
        Player target = player(c, club, Position.DEF, 80);
        Player differentPosition = player(c, club, Position.MID, 95); // strong, but wrong position
        replaceSquad(c, club, List.of(target, differentPosition));

        assertThat(sellerContextFactory.build(target, club).noCover()).isTrue();
    }

    @Test
    void aLoneStarterWithNoSquadmatesAtAllHasNoCover() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);
        Player target = player(c, club, Position.DEF, 80);
        replaceSquad(c, club, List.of(target));

        assertThat(sellerContextFactory.build(target, club).noCover()).isTrue();
    }

    @Test
    void healthDelegatesToTheFinancialHealthEvaluator() {
        Career c = createCareer();
        Club club = anAiClub(c);
        Player target = player(c, club, Position.MID, 70);
        replaceSquad(c, club, List.of(target));

        makeDesperate(club);
        assertThat(sellerContextFactory.build(target, club).health()).isEqualTo(DESPERATE);

        makeHealthy(club);
        assertThat(sellerContextFactory.build(target, club).health()).isEqualTo(HEALTHY);
    }

    @Test
    void listedFlagPassesThroughFromThePlayer() {
        Career c = createCareer();
        Club club = anAiClub(c);
        makeHealthy(club);
        Player unlistedPlayer = player(c, club, Position.MID, 70);
        Player listedPlayer = player(c, club, Position.MID, 65);
        listedPlayer.listForTransfer(ListedReason.SURPLUS);
        replaceSquad(c, club, List.of(unlistedPlayer, listedPlayer));

        assertThat(sellerContextFactory.build(unlistedPlayer, club).listed()).isFalse();
        assertThat(sellerContextFactory.build(listedPlayer, club).listed()).isTrue();
    }
}
