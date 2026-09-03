package br.com.carvoeiromanager.loan;

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
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.transfer.AiListingEngine;
import br.com.carvoeiromanager.transfer.AiTransferEngine;
import br.com.carvoeiromanager.transfer.CompletedTransferResponse;
import br.com.carvoeiromanager.transfer.TransferDirection;
import br.com.carvoeiromanager.transfer.TransferOffer;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import br.com.carvoeiromanager.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A borrowed player is nobody's to trade — and the manager's own exits are only half of that claim.
 *
 * <p>{@code BorrowedPlayerDisposalIT} closed the front door: the club that borrowed a player cannot
 * terminate, sell or list him. This suite closes the side doors, which reach the identical corrupted
 * row from other directions:
 * <ul>
 *   <li><b>Buying one.</b> The seller named on the offer is whoever holds him, which for a borrowed
 *       player is not his owner: the club that gets paid never owned him, the club that does is
 *       never asked, and {@code TransferExecutor} moves {@code club_id} while leaving
 *       {@code owner_club_id} pointing at the original owner.</li>
 *   <li><b>AI clubs trading one between themselves.</b> Entirely inside the AI world, so it is
 *       invisible to the manager until a loanee turns up at a third club — with his owner still
 *       being charged his wage share.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class BorrowedPlayerTradeGuardIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired TransferService transferService;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired AiListingEngine aiListingEngine;
    @Autowired AiTransferEngine aiTransferEngine;

    /** Inside {@code TransferWindow}'s pre-season range, so the buy paths are not window-gated. */
    private static final LocalDate WINDOW_OPEN = LocalDate.of(2026, 1, 20);

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("borrowtrade_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** The AI clubs of this save, lowest id first — a stable order over an unordered query. */
    private List<Club> aiClubs(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .filter(club -> !club.getId().equals(c.clubId()))
                .sorted(Comparator.comparing(Club::getId))
                .toList();
    }

    /**
     * Lends a non-GK from {@code ownerClubId} to {@code borrowerClubId}, written straight onto the
     * row — the exact end-state {@code LoanService.execute} produces.
     */
    private Player lend(UUID ownerClubId, UUID borrowerClubId) {
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(ownerClubId).stream()
                .filter(x -> x.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        p.setContractYears(3);
        p.loanTo(borrowerClubId, 50, true);
        playerRepository.save(p);
        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getOwnerClubId()).isEqualTo(ownerClubId);
        assertThat(reloaded.getClubId()).isEqualTo(borrowerClubId);
        return reloaded;
    }

    /** Every loaned player's whereabouts, so a run can be checked for having moved one. */
    private Map<UUID, UUID> loanedPlayerLocations(Career c) {
        Map<UUID, UUID> byId = new HashMap<>();
        playerRepository.findBySaveGameId(c.id()).stream()
                .filter(Player::isOnLoan)
                .forEach(p -> byId.put(p.getId(), p.getClubId()));
        return byId;
    }

    // ---- the buying side ----

    @Test
    void theManagerCannotBidForAPlayerAnotherClubOnlyBorrowed() {
        Career c = createCareer();
        List<Club> ai = aiClubs(c);
        Player borrowed = lend(ai.get(0).getId(), ai.get(1).getId());
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        assertThatThrownBy(() -> transferService.makeOffer(c.id(), c.userId(), borrowed.getId(),
                new BigDecimal("900000.00"), new BigDecimal("500000.00"), 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        Player after = playerRepository.findById(borrowed.getId()).orElseThrow();
        assertThat(after.getClubId()).isEqualTo(ai.get(1).getId());
        assertThat(after.getOwnerClubId()).isEqualTo(ai.get(0).getId());
        assertThat(after.getLoanWageShare()).isEqualTo(50);
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
        assertThat(transferOfferRepository.findBySaveGameIdAndPlayerIdAndStatusIn(c.id(),
                borrowed.getId(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED,
                        TransferOfferStatus.ACCEPTED, TransferOfferStatus.REJECTED)))
                .as("refused before the offer row is written, so nothing is left behind to accept")
                .isEmpty();
    }

    /**
     * The second half of the same negotiation. {@code acceptCounter} re-reads the player and used to
     * go straight to {@code completeBuy}, so a player who became a loanee between the counter and the
     * accept was bought anyway — the club paying a fee to a seller for a player it was already
     * holding on loan, and keeping the loan besides.
     */
    @Test
    void acceptingACounterForAPlayerSinceTakenOnLoanIsRefused() {
        Career c = createCareer();
        List<Club> ai = aiClubs(c);
        Player target = playerRepository.findByClubIdAndYouthProspectFalse(ai.get(0).getId()).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        // A live counter on him, from when he was still an ordinary target.
        TransferOffer offer = TransferOffer.create(c.id(), target.getId(), c.clubId(),
                ai.get(0).getId(), TransferDirection.OUTGOING, new BigDecimal("900000.00"), 0,
                new BigDecimal("500000.00"), 3);
        offer.markCountered(new BigDecimal("1000000.00"));
        transferOfferRepository.save(offer);

        // Then the managed club borrows him instead.
        target.setContractYears(3);
        target.loanTo(c.clubId(), 50, true);
        playerRepository.save(target);
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        assertThatThrownBy(() -> transferService.acceptCounter(c.id(), c.userId(), offer.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        Player after = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(after.isOnLoan()).isTrue();
        assertThat(after.getOwnerClubId()).isEqualTo(ai.get(0).getId());
        assertThat(after.getClubId()).isEqualTo(c.clubId());
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .as("no fee may be paid for a player the club is already holding on loan")
                .isEqualByComparingTo(balanceBefore);
        assertThat(transferOfferRepository.findById(offer.getId()).orElseThrow().getStatus())
                .isEqualTo(TransferOfferStatus.COUNTERED);
    }

    // ---- the AI engines ----

    /**
     * The listing engine's SURPLUS floor lists a club's <b>weakest non-GK</b> when nothing else has
     * fired, which is precisely the slot a borrowed player can fall into. The fixture makes him that
     * player unambiguously — overall 1 at the borrowing club — and then neutralises the two earlier
     * triggers (morale zeroed, round 0, so neither PLAYER_REQUEST nor UNUSED can fire) and clears
     * the club's existing listings, so the SURPLUS floor is the only rule left to run.
     *
     * <p>Without the fix he is the listing. With it, the floor reaches past him to the weakest
     * player the club actually owns — which the control assertion requires, so a rule that simply
     * stopped the engine listing anything would not pass here.
     */
    @Test
    void theAiListingEngineNeverListsABorrowedPlayer() {
        Career c = createCareer();
        List<Club> ai = aiClubs(c);
        UUID borrowerClubId = ai.get(1).getId();
        Player borrowed = lend(ai.get(0).getId(), borrowerClubId);

        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(borrowerClubId);
        squad.forEach(p -> {
            p.unlist();
            p.applyMorale(-p.getMorale());   // no PLAYER_REQUEST; round 0 rules out UNUSED
        });
        Player weakest = playerRepository.findById(borrowed.getId()).orElseThrow();
        weakest.setOverall(1);               // the unambiguous SURPLUS pick
        playerRepository.saveAll(squad);
        playerRepository.save(weakest);
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(borrowerClubId))
                .noneMatch(Player::isTransferListed);

        aiListingEngine.refreshListings(c.id(), c.clubId(), 0, WINDOW_OPEN);

        List<Player> after = playerRepository.findByClubIdAndYouthProspectFalse(borrowerClubId);
        assertThat(after)
                .as("the borrowing club may not put someone else's player on the market")
                .noneMatch(p -> p.getId().equals(borrowed.getId()) && p.isTransferListed());
        assertThat(after.stream().filter(Player::isTransferListed))
                .as("the engine must still have listed somebody, or the exclusion above proves nothing")
                .isNotEmpty()
                .allMatch(p -> p.getListedReason() == ListedReason.SURPLUS);
    }

    /**
     * The strong form: after every run, <b>no player whose {@code owner_club_id} is set has moved</b>
     * — which catches a borrowed player being traded regardless of which club did it or why.
     *
     * <p>The planted loanee is transfer-listed on the row (a listing that survives the loan: neither
     * {@link Player#loanTo} nor {@code LoanService.execute} clears the flag, which is exactly how one
     * reaches a borrower already carrying it) so he sits in the tradeable pool the engine draws from.
     * Several seeded market days are run so the engine has real business to do, and the control
     * assertion requires that it actually completed a trade — otherwise "he never moved" would only
     * mean "nothing moved".
     */
    @Test
    void theAiTransferEngineNeverTradesABorrowedPlayer() {
        Career c = createCareer();
        List<Club> ai = aiClubs(c);
        Player borrowed = lend(ai.get(0).getId(), ai.get(1).getId());
        borrowed.listForTransfer(ListedReason.SURPLUS);
        playerRepository.save(borrowed);

        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        Map<UUID, UUID> before = loanedPlayerLocations(c);
        assertThat(before).containsKey(borrowed.getId());

        int trades = 0;
        for (int day = 0; day < 10; day++) {
            List<CompletedTransferResponse> completed = aiTransferEngine.run(save, WINDOW_OPEN.plusDays(day));
            trades += completed.size();

            Map<UUID, UUID> now = loanedPlayerLocations(c);
            assertThat(now.keySet())
                    .as("no loan may be dissolved by a trade")
                    .containsAll(before.keySet());
            before.forEach((playerId, clubId) -> assertThat(now.get(playerId))
                    .as("a player on loan may not be moved by the AI market")
                    .isEqualTo(clubId));
        }

        assertThat(trades)
                .as("the engine must have traded something, or nothing moving proves nothing")
                .isPositive();
        Player after = playerRepository.findById(borrowed.getId()).orElseThrow();
        assertThat(after.getClubId()).isEqualTo(ai.get(1).getId());
        assertThat(after.getOwnerClubId()).isEqualTo(ai.get(0).getId());
        assertThat(after.isTransferListed())
                .as("he stays listed throughout — the pool filter, not an unlisting, is what spares him")
                .isTrue();
    }
}
