package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.contract.ContractValuation;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceBuyingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired ContractValuation contractValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("buy_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player anAiPlayer(Career c) {
        UUID other = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        return playerRepository.findByClubId(other).get(0);
    }

    /**
     * A buy target with a context-neutral ask factor (1.00 x 1.00 = 1.00), matching the old
     * flat-ratio negotiation: not listed, not a top-11 "key player" in their squad (overall 20 is
     * below any generated player), has a same-position squadmate as cover, and their club is
     * explicitly HEALTHY. Tests asserting the plain 0.95/0.70/1.10 thresholds use this instead of
     * {@link #anAiPlayer} so they don't depend on the squad's generated (non-deterministic)
     * overalls, positions, or club balance.
     */
    private Player aNeutralAiPlayer(Career c) {
        UUID otherClubId = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        Club otherClub = clubRepository.findById(otherClubId).orElseThrow();
        otherClub.setBalance(new BigDecimal("999999999.00")); // HEALTHY
        clubRepository.save(otherClub);

        Player target = Player.create(c.id(), otherClubId, "Neutral Target",
                br.com.carvoeiromanager.player.Position.MID, 25, "Brasil", 60, 60, 60, 60, 10, 60, 20);
        Player cover = Player.create(c.id(), otherClubId, "Neutral Cover",
                br.com.carvoeiromanager.player.Position.MID, 25, "Brasil", 60, 60, 60, 60, 10, 60, 20);
        playerRepository.save(cover);
        playerRepository.save(target);
        return target;
    }

    private BigDecimal pct(BigDecimal value, String factor) {
        return value.multiply(new BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal okWage(Player p) {
        return contractValuation.wageDemand(p.getOverall(), p.getAge());
    }

    @Test
    void offeringFullValueExecutesTheTransfer() {
        Career c = createCareer();
        Player target = aNeutralAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aCompletedBuyClearsTheBoughtPlayersListing() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        // Given the target is transfer-listed by their AI club (as every market player is)
        target.listForTransfer(br.com.carvoeiromanager.player.ListedReason.SURPLUS);
        playerRepository.save(target);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        Player bought = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(bought.getClubId()).isEqualTo(c.clubId());
        // The listing must not travel with the player to their new club.
        assertThat(bought.isTransferListed()).isFalse();
        assertThat(bought.getListedReason()).isNull();
    }

    @Test
    void aLowOfferIsCounteredAndCanBeAccepted() {
        Career c = createCareer();
        Player target = aNeutralAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        TransferOfferResponse countered = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.80"), okWage(target), 3);
        assertThat(countered.status()).isEqualTo("COUNTERED");
        assertThat(countered.counterAmount()).isEqualByComparingTo(pct(value, "1.10"));

        TransferOfferResponse done = transferService.acceptCounter(c.id(), c.userId(), countered.id());
        assertThat(done.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aDerisoryOfferIsRejected() {
        Career c = createCareer();
        Player target = aNeutralAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), pct(value, "0.50"), okWage(target), 3);
        assertThat(r.status()).isEqualTo("REJECTED");
    }

    @Test
    void anOfferOverBudgetIsRejectedWithInsufficientFunds() {
        Career c = createCareer();
        Player target = anAiPlayer(c);
        BigDecimal balance = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        assertThatThrownBy(() -> transferService.makeOffer(
                c.id(), c.userId(), target.getId(), balance.add(new BigDecimal("1")), okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void offersAreBlockedWhenTheWindowIsClosed() {
        Career c = createCareer();
        // Move the save date outside any transfer window (May 1 is past pre-season, before mid-season).
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1));
        saveGameRepository.save(save);
        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        assertThatThrownBy(() -> transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.TRANSFER_WINDOW_CLOSED);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void foreignUserCannotBrowseTheMarket() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> transferService.getMarket(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void marketListsOnlyOtherClubsPlayersWithAValue() {
        Career c = createCareer();
        var market = transferService.getMarket(c.id(), c.userId());
        // createCareer's day-one market pass already lists some AI players (SURPLUS floor etc.).
        assertThat(market).isNotEmpty();
        assertThat(market).noneMatch(m -> m.clubId().equals(c.clubId()));
        assertThat(market).allMatch(m -> m.value().signum() > 0);
    }

    @Test
    void marketOnlyIncludesTransferListedPlayers() {
        Career c = createCareer();
        var market = transferService.getMarket(c.id(), c.userId());
        List<UUID> marketPlayerIds = market.stream().map(MarketPlayerResponse::playerId).toList();

        List<Player> allOtherClubsPlayers = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null && !p.getClubId().equals(c.clubId()))
                .toList();
        List<UUID> actuallyListedIds = allOtherClubsPlayers.stream()
                .filter(Player::isTransferListed)
                .map(Player::getId)
                .toList();
        List<UUID> notListedIds = allOtherClubsPlayers.stream()
                .filter(p -> !p.isTransferListed())
                .map(Player::getId)
                .toList();

        assertThat(actuallyListedIds).isNotEmpty(); // non-vacuous: something was actually listed
        assertThat(notListedIds).isNotEmpty(); // non-vacuous: something was actually excluded
        assertThat(marketPlayerIds).containsExactlyInAnyOrderElementsOf(actuallyListedIds);
        assertThat(marketPlayerIds).doesNotContainAnyElementsOf(notListedIds);
        assertThat(market).allMatch(m -> m.listedReason() != null);
    }

    @Test
    void youthProspectsDoNotCountAgainstTheSquadCapWhenBuying() {
        Career c = createCareer();
        int seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        int toAdd = Math.max(0, 30 - seniors) + 5;
        for (int i = 0; i < toAdd; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i,
                    br.com.carvoeiromanager.player.Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            prospect.markAsProspect(80);
            playerRepository.save(prospect);
        }
        assertThat(playerRepository.findByClubId(c.clubId()).size()).isGreaterThanOrEqualTo(30);

        Player target = aNeutralAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }

    // ---- Phase C: context-aware negotiation ----

    @Test
    void aListedPlayerAcceptsALowballOfferBecauseTheSellerIsKeenToMove() {
        Career c = createCareer();
        Player target = aNeutralAiPlayer(c);
        target.listForTransfer(br.com.carvoeiromanager.player.ListedReason.SURPLUS);
        playerRepository.save(target);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        // askFactor = 0.85 (listed) x 1.00 (healthy) = 0.85; a lowball 85% of value clears the
        // 0.95 x effective (0.8075 x value) accept bar even though it's well under full value.
        BigDecimal lowball = pct(value, "0.85");

        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), lowball, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }

    /** Wipes the club's current (non-youth) squad and replaces it with exactly these players. */
    private void replaceSquad(Club club, List<Player> players) {
        playerRepository.deleteAll(playerRepository.findByClubId(club.getId()));
        playerRepository.saveAll(players);
    }

    @Test
    void anUnlistedKeyPlayerWithoutCoverIsCounteredWellAboveValue() {
        Career c = createCareer();
        UUID otherClubId = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        Club otherClub = clubRepository.findById(otherClubId).orElseThrow();
        otherClub.setBalance(new BigDecimal("999999999.00")); // HEALTHY
        clubRepository.save(otherClub);
        // A lone DEF: sole player at the club means top rank (key player) and no cover at all.
        Player target = Player.create(c.id(), otherClubId, "Star Defender",
                br.com.carvoeiromanager.player.Position.DEF, 25, "Brasil", 60, 60, 60, 80, 10, 60, 75);
        replaceSquad(otherClub, List.of(target));
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        // askFactor = (1.00 + 0.20 keyPlayer + 0.15 noCover) x 1.00 healthy = 1.35.
        BigDecimal effective = pct(value, "1.35");

        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), value, okWage(target), 3);

        assertThat(r.status()).isEqualTo("COUNTERED");
        assertThat(r.counterAmount()).isEqualByComparingTo(pct(effective, "1.10"));
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(otherClubId); // still with the seller: only countered, not accepted
    }

    @Test
    void aDesperateSellerAcceptsALowballOfferForAnOtherwiseOrdinaryPlayer() {
        Career c = createCareer();
        Player target = aNeutralAiPlayer(c);
        Club sellerClub = clubRepository.findById(target.getClubId()).orElseThrow();
        sellerClub.setBalance(BigDecimal.ZERO); // DESPERATE (below one month's wage bill)
        clubRepository.save(sellerClub);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        // askFactor = 1.00 (neutral) x 0.75 (desperate) = 0.75; accept >= 0.95 x 0.75 x value.
        BigDecimal effective = pct(value, "0.75");
        BigDecimal lowball = pct(effective, "0.95");
        assertThat(lowball).isLessThan(value); // genuinely a lowball relative to plain value

        TransferOfferResponse r = transferService.makeOffer(
                c.id(), c.userId(), target.getId(), lowball, okWage(target), 3);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(target.getId()).orElseThrow().getClubId())
                .isEqualTo(c.clubId());
    }

    @Test
    void makingAnOfferWhenTheSquadIsAtTheCapIsRejected() {
        Career c = createCareer();
        int current = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size();
        for (int i = 0; i < SquadRules.MAX_SQUAD_SIZE - current; i++) {
            Player filler = Player.create(c.id(), c.clubId(), "Reserva " + i,
                    br.com.carvoeiromanager.player.Position.MID, 22, "Brasil",
                    55, 55, 55, 45, 10, 60, 55);
            playerRepository.save(filler);
        }
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).size())
                .isEqualTo(SquadRules.MAX_SQUAD_SIZE);

        Player target = anAiPlayer(c);
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());

        assertThatThrownBy(() ->
                transferService.makeOffer(c.id(), c.userId(), target.getId(), value, okWage(target), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.SQUAD_FULL));
    }
}

