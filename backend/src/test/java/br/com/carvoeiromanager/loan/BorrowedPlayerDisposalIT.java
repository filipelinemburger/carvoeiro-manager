package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.contract.ContractService;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A borrowed player is in the managed squad but is not the managed club's to dispose of.
 *
 * <p>{@code players.club_id} means <b>where he plays</b>, so every "is he mine?" lookup that reads
 * it alone — terminate, renew, list, sell, and the unsolicited-bid generator's candidate pool —
 * cannot tell a borrowed player from an owned one. {@code owner_club_id} is the only column that
 * can, and until this suite existed nothing on those paths consulted it. The two reachable
 * outcomes were a player who is simultaneously a free agent and on loan (terminate), and a third
 * club holding a player whose original owner keeps paying his wage share forever (sell).
 *
 * <p>Every test here asserts the refusal <b>and</b> that the row is untouched: a guard that throws
 * after a partial mutation would leave exactly the corruption it exists to prevent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class BorrowedPlayerDisposalIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired ContractService contractService;
    @Autowired TransferService transferService;
    @Autowired TransferOfferRepository transferOfferRepository;

    /** The pre-season window, which {@code TransferWindow} opens for 42 days from Jan 15. */
    private static final LocalDate WINDOW_OPEN_START = LocalDate.of(2026, 1, 15);
    private static final int WINDOW_OPEN_DAYS = 42;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    /** The borrowed player plus the club he is borrowed FROM, so tests can assert both ends. */
    private record Borrowed(Player player, UUID ownerClubId) { }

    private Career createCareer() {
        User user = User.create("borrowguard_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * Puts the managed club on the receiving end of a loan, written straight onto the row: this is
     * the exact end-state {@code LoanService.execute} produces, and going through the negotiation
     * would only add a plausibility fixture that has nothing to do with what is under test here.
     * A non-GK is chosen so the squad-floor goalkeeper rule can never be what refuses instead.
     */
    private Borrowed borrowAPlayer(Career c) {
        UUID ownerClubId = aRivalClubId(c);
        Player target = playerRepository.findByClubIdAndYouthProspectFalse(ownerClubId).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        target.setContractYears(3);
        target.loanTo(c.clubId(), 50, false);
        playerRepository.save(target);
        Player reloaded = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getClubId()).isEqualTo(c.clubId());
        return new Borrowed(reloaded, ownerClubId);
    }

    /** Everything a disposal would have moved: the loan is intact and he is still where he was. */
    private void assertUntouched(Borrowed b) {
        Player after = playerRepository.findById(b.player().getId()).orElseThrow();
        assertThat(after.isOnLoan()).isTrue();
        assertThat(after.getOwnerClubId()).isEqualTo(b.ownerClubId());
        assertThat(after.getClubId()).isEqualTo(b.player().getClubId());
        assertThat(after.getLoanWageShare()).isEqualTo(b.player().getLoanWageShare());
        assertThat(after.getLoanMayFaceOwner()).isEqualTo(b.player().getLoanMayFaceOwner());
        assertThat(after.getContractYears()).isEqualTo(b.player().getContractYears());
        assertThat(after.getWage()).isEqualByComparingTo(b.player().getWage());
        assertThat(after.getReleasedByClubId()).isNull();
        assertThat(after.isTransferListed()).isEqualTo(b.player().isTransferListed());
    }

    /**
     * Terminating him would call {@code Player.releaseFrom}, which nulls {@code club_id} and zeroes
     * his terms while leaving {@code owner_club_id} set — a row that is a free agent and a loanee at
     * the same time, which the loans spec calls a load-bearing invariant. The owner loses the asset
     * with no compensation, and the next rollover "un-releases" him with a zeroed contract.
     */
    @Test
    void terminatingABorrowedPlayerIsRefusedAndChangesNothing() {
        Career c = createCareer();
        Borrowed b = borrowAPlayer(c);
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        assertThatThrownBy(() -> contractService.terminateContract(c.id(), c.userId(), b.player().getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        assertUntouched(b);
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .as("no termination fee may be paid for a player the club does not own")
                .isEqualByComparingTo(balanceBefore);
    }

    /**
     * Selling him sets {@code club_id} to the buyer and never touches {@code owner_club_id}: a THIRD
     * club then holds him while {@code isOnLoan()} still reports true against the original owner,
     * who goes on being charged his wage share every processed month, forever.
     */
    @Test
    void sellingABorrowedPlayerIsRefusedAndChangesNothing() {
        Career c = createCareer();
        Borrowed b = borrowAPlayer(c);
        UUID bidderClubId = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()) && !id.equals(b.ownerClubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
        TransferOffer bid = transferOfferRepository.save(TransferOffer.create(
                c.id(), b.player().getId(), bidderClubId, c.clubId(),
                TransferDirection.INCOMING, new BigDecimal("900000.00"), 0, null, null));

        assertThatThrownBy(() -> transferService.respondToIncoming(
                c.id(), c.userId(), bid.getId(), "ACCEPT", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        assertUntouched(b);
        assertThat(transferOfferRepository.findById(bid.getId()).orElseThrow().getStatus())
                .as("the bid must be left open for the manager to reject himself, not silently settled")
                .isEqualTo(TransferOfferStatus.PENDING);
    }

    /** Listing him invites exactly the sale the guard above refuses; refuse it a step earlier. */
    @Test
    void listingABorrowedPlayerIsRefusedAndChangesNothing() {
        Career c = createCareer();
        Borrowed b = borrowAPlayer(c);

        assertThatThrownBy(() -> transferService.listPlayer(c.id(), c.userId(), b.player().getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        assertUntouched(b);
        assertThat(playerRepository.findById(b.player().getId()).orElseThrow().isTransferListed()).isFalse();
    }

    /** His contract is with his owner. The borrowing club has nothing to renew. */
    @Test
    void renewingABorrowedPlayersContractIsRefusedAndChangesNothing() {
        Career c = createCareer();
        Borrowed b = borrowAPlayer(c);

        assertThatThrownBy(() -> contractService.renewContract(
                c.id(), c.userId(), b.player().getId(), new BigDecimal("500000.00"), 4))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.PLAYER_ALREADY_ON_LOAN));

        assertUntouched(b);
    }

    /**
     * The unsolicited-bid generator iterates every player at the managed club, so a borrowed one was
     * a candidate like any other — and a bid the manager can only ever be refused on is an offer of
     * a deal that does not exist.
     *
     * <p>He is transfer-listed here (on the row directly — the service refuses to list him, which is
     * the test above) purely to maximise his draw rate: listed candidates sort first and roll at
     * {@code LISTED_BID_CHANCE = 0.45} rather than {@code 0.15}. Over every open day of the
     * pre-season window that is a near-certain bid without the fix, so a clean run here is evidence
     * and not luck. The control assertion — that OTHER managed players did draw bids — is what makes
     * it so: it proves the generator ran and had appetite, and simply never reached for him.
     */
    @Test
    void theIncomingBidGeneratorNeverBidsForABorrowedPlayer() {
        Career c = createCareer();
        Borrowed b = borrowAPlayer(c);
        Player listed = playerRepository.findById(b.player().getId()).orElseThrow();
        listed.listForTransfer(ListedReason.OWNER);
        playerRepository.save(listed);

        for (int day = 0; day < WINDOW_OPEN_DAYS; day++) {
            transferService.generateIncomingOffers(c.id(), WINDOW_OPEN_START.plusDays(day));
        }

        List<TransferOffer> bids = transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(),
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED));
        assertThat(bids)
                .as("the generator must have had appetite, or the absence below proves nothing")
                .isNotEmpty();
        assertThat(bids)
                .extracting(TransferOffer::getPlayerId)
                .doesNotContain(b.player().getId());
    }
}
