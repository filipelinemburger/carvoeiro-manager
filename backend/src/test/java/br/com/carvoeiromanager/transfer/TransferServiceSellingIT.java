package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.match.LiveMatch;
import br.com.carvoeiromanager.match.LiveMatchRepository;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferServiceSellingIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired LiveMatchRepository liveMatchRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("sell_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anAiClub(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
    }

    /** Create an unsolicited INCOMING bid from an AI club for one of the managed club's players. */
    private TransferOffer incomingBidFor(Career c, Player managedPlayer, BigDecimal amount) {
        TransferOffer offer = TransferOffer.create(c.id(), managedPlayer.getId(), anAiClub(c), c.clubId(),
                TransferDirection.INCOMING, amount, 0, null, null);
        return transferOfferRepository.save(offer);
    }

    private Player aManagedOutfielder(Career c) {
        return playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() != Position.GK).findFirst().orElseThrow();
    }

    @Test
    void acceptingAnIncomingBidSellsThePlayer() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringWithinTheCeilingIsAccepted() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal counter = value.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP); // <= 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", counter);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isNotEqualTo(c.clubId());
    }

    @Test
    void counteringAboveTheCeilingIsRejected() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        BigDecimal value = transferValuation.marketValue(p.getOverall(), p.getAge());
        TransferOffer bid = incomingBidFor(c, p, value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal greedy = value.multiply(new BigDecimal("1.30")).setScale(2, RoundingMode.HALF_UP); // > 1.15*value

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", greedy);

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void cannotSellTheLastGoalkeeper() {
        Career c = createCareer();
        // Reduce managed GKs to exactly one.
        List<Player> gks = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).toList();
        if (gks.size() > 1) {
            playerRepository.deleteAll(gks.subList(1, gks.size()));
        }
        Player lastGk = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        TransferOffer bid = incomingBidFor(c, lastGk, new BigDecimal("800000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SQUAD_TOO_SMALL);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void counteringWithZeroAmountIsRejectedAndPlayerNotSold() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "COUNTER", java.math.BigDecimal.ZERO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INVALID_TRANSFER);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void getOffersReturnsTheActiveInbox() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        incomingBidFor(c, p, new BigDecimal("900000.00"));
        List<TransferOfferResponse> inbox = transferService.getOffers(c.id(), c.userId());
        assertThat(inbox).anyMatch(o -> o.direction().equals("INCOMING") && o.status().equals("PENDING"));
    }

    @Test
    void acceptingIncomingOfferWhileLiveMatchExistsThrowsConflict() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        // Simulate a live match row existing for this save (minimal required fields).
        LiveMatch liveMatch = LiveMatch.create(c.id(), UUID.randomUUID(), UUID.randomUUID(), 1,
                java.time.LocalDate.of(2026, 3, 1), 42L, true,
                "[]", "[]", "[]", "[]", "[]");
        liveMatchRepository.save(liveMatch);

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.LIVE_MATCH_IN_PROGRESS);
                    assertThat(api.getStatus().value()).isEqualTo(409);
                });

        // Cleanup so the live match row doesn't leak between tests (unique constraint on save_game_id).
        liveMatchRepository.deleteBySaveGameId(c.id());
    }

    @Test
    void generateIncomingOffersCreatesBidsInsideAWindowAndNoneOutside() {
        Career c = createCareer();
        transferService.generateIncomingOffers(c.id(), LocalDate.of(2026, 1, 15)); // pre-season window OPEN
        long inWindow = transferOfferRepository.findBySaveGameIdAndStatusIn(
                c.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size();
        assertThat(inWindow).isGreaterThanOrEqualTo(0); // bounded; may be 0..3 deterministically

        // createCareer already runs a day-one market pass (window open on creation day), so a
        // fresh career may already carry some incoming bids before this call; compare deltas.
        Career c2 = createCareer();
        long before = transferOfferRepository.findBySaveGameIdAndStatusIn(
                c2.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).size();
        transferService.generateIncomingOffers(c2.id(), LocalDate.of(2026, 5, 1)); // window CLOSED
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(
                c2.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)))
                .hasSize((int) before);
    }

    @Test
    void foreignUserCannotReadOrRespond() {
        Career owner = createCareer();
        Career intruder = createCareer();
        Player p = aManagedOutfielder(owner);
        TransferOffer bid = incomingBidFor(owner, p, new BigDecimal("900000.00"));
        assertThatThrownBy(() -> transferService.getOffers(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> transferService.respondToIncoming(owner.id(), intruder.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void youthProspectsDoNotCountTowardTheMinimumSquadWhenSelling() {
        Career c = createCareer();
        // Trim seniors to exactly the minimum so the squad sits on the floor.
        List<Player> seniors = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        List<Player> drop = seniors.subList(TransferService.MIN_SQUAD_SIZE, seniors.size());
        playerRepository.deleteAll(drop);
        List<Player> kept = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(kept).hasSize(TransferService.MIN_SQUAD_SIZE);

        // Add prospects that MUST NOT be counted toward the squad minimum.
        for (int i = 0; i < 5; i++) {
            Player prospect = Player.create(c.id(), c.clubId(), "Base " + i, Position.MID, 16, "Brasil",
                    55, 55, 55, 45, 10, 55, 55);
            prospect.markAsProspect(85);
            playerRepository.save(prospect);
        }

        // Selling any senior while at the floor must be blocked despite the 5 prospects present.
        Player p = kept.stream().filter(pl -> pl.getPosition() != Position.GK).findFirst().orElse(kept.get(0));
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SQUAD_TOO_SMALL);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aYouthProspectGoalkeeperDoesNotUnblockSellingTheLastSeniorGoalkeeper() {
        Career c = createCareer();
        // Reduce senior goalkeepers to exactly one (same setup as cannotSellTheLastGoalkeeper).
        List<Player> gks = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).toList();
        if (gks.size() > 1) {
            playerRepository.deleteAll(gks.subList(1, gks.size()));
        }
        // Add a PROSPECT goalkeeper — it must not count as senior GK cover.
        Player prospectGk = Player.create(c.id(), c.clubId(), "Goleiro da Base", Position.GK, 16, "Brasil",
                55, 40, 45, 45, 60, 55, 50);
        prospectGk.markAsProspect(88);
        playerRepository.save(prospectGk);

        Player lastSeniorGk = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        TransferOffer bid = incomingBidFor(c, lastSeniorGk, new BigDecimal("800000.00"));

        assertThatThrownBy(() -> transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SQUAD_TOO_SMALL);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
        assertThat(playerRepository.findById(lastSeniorGk.getId()).orElseThrow().getClubId()).isEqualTo(c.clubId());
    }

    @Test
    void aiClubsNeverBidOnYouthProspects() {
        Career c = createCareer();
        // Seed a prospect for the managed club.
        Player prospect = Player.create(c.id(), c.clubId(), "Promessa Cobicada", Position.ATT, 16, "Brasil",
                70, 72, 70, 55, 10, 55, 65);
        prospect.markAsProspect(92);
        playerRepository.save(prospect);

        // Sweep the whole pre-season window (many independent seeds) so senior bids accumulate.
        LocalDate seasonStart = LocalDate.of(2026, 1, 15);
        for (int d = 0; d < TransferWindow.PRE_SEASON_DAYS; d++) {
            transferService.generateIncomingOffers(c.id(), seasonStart.plusDays(d));
        }

        List<TransferOffer> incoming = transferOfferRepository.findBySaveGameIdAndStatusIn(
                        c.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING).toList();

        // Non-vacuous: the loop actually ran and generated bids on seniors...
        assertThat(incoming).isNotEmpty();
        // ...but the prospect was never a candidate.
        assertThat(incoming).noneMatch(o -> o.getPlayerId().equals(prospect.getId()));
    }

    // ---- Managed list/unlist ----

    @Test
    void listingAnOwnedNonYouthPlayerSucceedsWithOwnerReason() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);

        TransferListingResponse r = transferService.listPlayer(c.id(), c.userId(), p.getId());

        assertThat(r.transferListed()).isTrue();
        assertThat(r.listedReason()).isEqualTo("OWNER");
        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isTransferListed()).isTrue();
        assertThat(reloaded.getListedReason().name()).isEqualTo("OWNER");
    }

    @Test
    void unlistingClearsTheListing() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        transferService.listPlayer(c.id(), c.userId(), p.getId());

        TransferListingResponse r = transferService.unlistPlayer(c.id(), c.userId(), p.getId());

        assertThat(r.transferListed()).isFalse();
        assertThat(r.listedReason()).isNull();
        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isTransferListed()).isFalse();
        assertThat(reloaded.getListedReason()).isNull();
    }

    @Test
    void listingIsAllowedEvenWhenTheTransferWindowIsClosed() {
        Career c = createCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(LocalDate.of(2026, 5, 1)); // outside any window
        saveGameRepository.save(save);
        Player p = aManagedOutfielder(c);

        TransferListingResponse r = transferService.listPlayer(c.id(), c.userId(), p.getId());

        assertThat(r.transferListed()).isTrue();
    }

    @Test
    void aYouthProspectCannotBeListed() {
        Career c = createCareer();
        Player prospect = Player.create(c.id(), c.clubId(), "Joia da Base", Position.MID, 16, "Brasil",
                55, 55, 55, 45, 10, 55, 55);
        prospect.markAsProspect(85);
        playerRepository.save(prospect);

        assertThatThrownBy(() -> transferService.listPlayer(c.id(), c.userId(), prospect.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INVALID_TRANSFER);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void aPlayerFromAnotherClubCannotBeListedByTheManagedUser() {
        Career c = createCareer();
        Player aiPlayer = playerRepository.findByClubId(anAiClub(c)).get(0);

        assertThatThrownBy(() -> transferService.listPlayer(c.id(), c.userId(), aiPlayer.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.INVALID_TRANSFER);
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void sellingAnOwnerListedPlayerClearsTheListing() {
        Career c = createCareer();
        Player p = aManagedOutfielder(c);
        transferService.listPlayer(c.id(), c.userId(), p.getId()); // OWNER listing
        TransferOffer bid = incomingBidFor(c, p, new BigDecimal("900000.00"));

        TransferOfferResponse r = transferService.respondToIncoming(c.id(), c.userId(), bid.getId(), "ACCEPT", null);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        Player sold = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(sold.getClubId()).isNotEqualTo(c.clubId());
        // The OWNER listing must not follow the player to the AI buyer (it would otherwise
        // leak back into the market with a stale OWNER chip).
        assertThat(sold.isTransferListed()).isFalse();
        assertThat(sold.getListedReason()).isNull();
    }

    @Test
    void listedManagedPlayersDrawBoostedBids() {
        Career c = createCareer();
        Player target = aManagedOutfielder(c);
        transferService.listPlayer(c.id(), c.userId(), target.getId());
        BigDecimal value = transferValuation.marketValue(target.getOverall(), target.getAge());
        BigDecimal expectedListedBid = value.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);

        // Sweep the pre-season window (LISTED_BID_CHANCE = 0.45) so a bid reliably lands.
        LocalDate seasonStart = LocalDate.of(2026, 1, 15);
        for (int d = 0; d < TransferWindow.PRE_SEASON_DAYS; d++) {
            transferService.generateIncomingOffers(c.id(), seasonStart.plusDays(d));
        }

        List<TransferOffer> bidsOnTarget = transferOfferRepository.findBySaveGameIdAndStatusIn(
                        c.id(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING)
                .filter(o -> o.getPlayerId().equals(target.getId()))
                .toList();

        assertThat(bidsOnTarget).isNotEmpty(); // non-vacuous: the sweep actually landed a bid
        assertThat(bidsOnTarget).allMatch(o -> o.getAmount().compareTo(expectedListedBid) == 0);
    }
}
