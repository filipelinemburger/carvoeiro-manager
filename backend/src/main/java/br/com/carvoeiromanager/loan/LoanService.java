package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadDeparture;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.transfer.SellerContext;
import br.com.carvoeiromanager.transfer.SellerContextFactory;
import br.com.carvoeiromanager.transfer.TransferWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Loans, in both directions. A loan is the third way out of the managed squad, so it runs the same
 * {@link SquadDeparture} contract selling and terminating do; what is specific to it is that the
 * contract must outlast the loan, and that the terms are negotiable.
 */
@Service
public class LoanService {

    /** A loan runs to the rollover, where contracts tick — so it must close inside one contract. */
    static final int MIN_CONTRACT_YEARS_TO_LOAN = 2;

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final LoanOfferRepository loanOfferRepository;
    private final LoanPlausibility loanPlausibility;
    private final LoanNegotiator loanNegotiator;
    private final SellerContextFactory sellerContextFactory;
    private final SquadDeparture squadDeparture;
    private final TransferWindow transferWindow;

    public LoanService(SaveGameService saveGameService, PlayerRepository playerRepository,
                       ClubRepository clubRepository, LoanOfferRepository loanOfferRepository,
                       LoanPlausibility loanPlausibility, LoanNegotiator loanNegotiator,
                       SellerContextFactory sellerContextFactory, SquadDeparture squadDeparture,
                       TransferWindow transferWindow) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.loanOfferRepository = loanOfferRepository;
        this.loanPlausibility = loanPlausibility;
        this.loanNegotiator = loanNegotiator;
        this.sellerContextFactory = sellerContextFactory;
        this.squadDeparture = squadDeparture;
        this.transferWindow = transferWindow;
    }

    /** The manager lends one of his own players to an AI club. */
    @Transactional
    public LoanOfferResponse offerLoanOut(UUID saveGameId, UUID userId, UUID playerId,
                                          UUID borrowerClubId, int ownerWageShare,
                                          boolean mayFaceOwner) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);
        // Before the lookup: a malformed body is a bad request, and diagnosing it after the player
        // search would answer PLAYER_NOT_FOUND for an offer whose player was never the problem.
        requireValidTerms(new LoanTerms(ownerWageShare, mayFaceOwner));

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> managedClubId.equals(p.getClubId()))
                .filter(p -> !p.isYouthProspect())
                .filter(p -> !p.isOnLoan())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player not found in your professional squad"));

        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(managedClubId), player);

        Club owner = clubRepository.findById(managedClubId).orElseThrow();
        Club borrower = clubRepository.findById(borrowerClubId)
                .filter(club -> club.getSaveGameId().equals(saveGameId))
                .filter(club -> !club.getId().equals(managedClubId))
                .orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found"));
        requireRoomFor(borrower.getId());

        return negotiate(save, player, owner, borrower,
                new LoanTerms(ownerWageShare, mayFaceOwner), false);
    }

    /** The manager borrows a player from the AI club that currently holds him. */
    @Transactional
    public LoanOfferResponse offerLoanIn(UUID saveGameId, UUID userId, UUID playerId,
                                         int ownerWageShare, boolean mayFaceOwner) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);
        requireValidTerms(new LoanTerms(ownerWageShare, mayFaceOwner));   // see offerLoanOut

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .filter(p -> p.getClubId() != null)
                .filter(p -> !managedClubId.equals(p.getClubId()))   // not already yours
                .filter(p -> !p.isYouthProspect())
                .filter(p -> !p.isOnLoan())
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "No such player at another club"));

        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        // The owner is not necessarily the managed club — Spec §8 does not restrict "cannot lend
        // itself below a fieldable squad" to the managed side, and an AI owner is otherwise never
        // checked against its own floor before it lends a player away.
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(player.getClubId()), player);
        requireRoomFor(managedClubId);

        Club owner = clubRepository.findById(player.getClubId()).orElseThrow();
        Club borrower = clubRepository.findById(managedClubId).orElseThrow();

        return negotiate(save, player, owner, borrower,
                new LoanTerms(ownerWageShare, mayFaceOwner), true);
    }

    /**
     * The shared core: decide, then either execute or record what the AI said. {@code aiIsOwner}
     * says which side the AI sits on, which is the only thing that differs between directions.
     * <p>
     * The wage-share bound is re-checked here even though both entry points already reject a
     * malformed one: the guard is idempotent and pure, and keeping it here means no future caller
     * of this shared core can persist a share the finance split could not honour. The entry points
     * check it first only so that a malformed body is diagnosed as such rather than as a missing
     * player.
     */
    LoanOfferResponse negotiate(SaveGame save, Player player, Club owner, Club borrower,
                                LoanTerms offered, boolean aiIsOwner) {
        requireValidTerms(offered);

        LoanOffer offer = LoanOffer.create(save.getId(), player.getId(), owner.getId(),
                borrower.getId(), offered.ownerWageShare(), offered.mayFaceOwner(),
                save.getCurrentRound());

        SellerContext ownerContext = sellerContextFactory.build(player, owner);
        Optional<LoanRefusal> refusal = loanPlausibility.check(
                ownerContext, player.getOverall(), borrower.getReputation());
        if (refusal.isPresent()) {
            offer.markRejected();
            loanOfferRepository.save(offer);
            return toResponse(offer, player, refusal.get());
        }

        LoanNegotiationResult result = loanNegotiator.respond(
                offered, player.getOverall(), borrower.getReputation(), aiIsOwner);
        // Exhaustive on purpose. LoanNegotiator.respond only ever answers ACCEPT or COUNTER today,
        // but "anything that is not a counter is an acceptance" would silently EXECUTE a loan the
        // day it learns to say REJECT. A loan moving a player is not a safe default.
        switch (result.decision()) {
            case COUNTER -> {
                offer.markCountered(result.counter().ownerWageShare(), result.counter().mayFaceOwner());
                loanOfferRepository.save(offer);
                return toResponse(offer, player, LoanRefusal.TERMS_REJECTED);
            }
            case ACCEPT -> {
                execute(save, player, borrower.getId(), offered);
                offer.markAccepted();
                loanOfferRepository.save(offer);
                return toResponse(offer, player, null);
            }
            default -> throw new ApiException(ErrorCode.INVALID_TRANSFER,
                    "The loan negotiator returned an unhandled decision: " + result.decision());
        }
    }

    /** Takes the AI's counter as offered. There is no counter-the-counter: accept or walk away. */
    @Transactional
    public LoanOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID offerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);

        LoanOffer offer = loanOfferRepository.findById(offerId)
                .filter(o -> o.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_OFFER_NOT_FOUND,
                        "Loan offer not found"));
        if (offer.getStatus() != LoanOfferStatus.COUNTERED) {
            throw new ApiException(ErrorCode.INVALID_TRANSFER, "No counter to accept on this offer");
        }
        requireWindowOpen(save);

        Player player = playerRepository.findById(offer.getPlayerId())
                .filter(p -> !p.isOnLoan())
                // He must still be at the club the offer named: he may have been sold, released
                // or lent elsewhere since the AI answered, and the offer's owner would then be
                // stale — executing it would move a player out of a club that no longer holds him.
                .filter(p -> offer.getOwnerClubId().equals(p.getClubId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "That player is no longer available"));
        requireLoanableContract(player);
        squadDeparture.requireNoLiveMatch(saveGameId);
        // Re-run the same departure guard offerLoanOut checks at offer time: the squad the offer
        // was built against may have shrunk (another sale, release or loan) by the time the AI's
        // counter is accepted, same as TransferService.respondToIncoming re-runs requireSellAllowed
        // on every accept branch rather than trusting the state at offer time.
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(offer.getOwnerClubId()), player);
        requireRoomFor(offer.getBorrowerClubId());

        execute(save, player, offer.getBorrowerClubId(),
                new LoanTerms(offer.getCounterOwnerWageShare(), offer.getCounterMayFaceOwner()));
        offer.markAccepted();
        loanOfferRepository.save(offer);
        return toResponse(offer, player, null);
    }

    /**
     * Moves the player and settles everything leaving the squad implies. The offer rejection is
     * not optional: a live bid that outlived the loan would meet a sell guard that now passes
     * trivially, because the player is no longer in the squad it counts.
     * <p>
     * The transfer listing is pulled the same way: a lent player is not the owner's to sell while
     * lent — {@link SquadDeparture#requireNotOnLoan} already refuses any offer against him — so a
     * listing that outlived the loan would only show him on the market, under the borrower's club
     * name, with no way to ever complete.
     */
    void execute(SaveGame save, Player player, UUID borrowerClubId, LoanTerms terms) {
        UUID formerClubId = player.getClubId();
        player.loanTo(borrowerClubId, terms.ownerWageShare(), terms.mayFaceOwner());
        player.unlist();
        playerRepository.save(player);
        squadDeparture.cleanManagedLineup(save.getId(), formerClubId, player.getId());
        squadDeparture.rejectOpenOffersFor(save.getId(), player.getId());
    }

    /**
     * A loan is a squad movement like any transfer: the backend already enforces the window for
     * transfers ({@code TransferService.requireWindowOpen}) and contracts
     * ({@code ContractService.requireWindowOpen}), so a loan closing that gate only in the client
     * would not be a rule at all — a direct API call would bypass it.
     */
    void requireWindowOpen(SaveGame save) {
        if (!transferWindow.isOpen(save.getCurrentDate())) {
            throw new ApiException(ErrorCode.TRANSFER_WINDOW_CLOSED, "The transfer window is closed");
        }
    }

    void requireLoanableContract(Player player) {
        if (player.getContractYears() < MIN_CONTRACT_YEARS_TO_LOAN) {
            throw new ApiException(ErrorCode.LOAN_CONTRACT_TOO_SHORT,
                    "A loan must end before the contract does; this one has "
                            + player.getContractYears() + " year(s) left");
        }
    }

    /**
     * There is no CHECK constraint behind {@code owner_wage_share}, and {@link Player#loanTo}
     * does not validate its argument either — so this is the one place standing between a
     * malformed request body and a persisted term that a later finance split could not honour.
     */
    void requireValidTerms(LoanTerms terms) {
        if (terms.ownerWageShare() < 0 || terms.ownerWageShare() > 100) {
            throw new ApiException(ErrorCode.LOAN_INVALID_TERMS,
                    "Owner wage share must be between 0 and 100, was " + terms.ownerWageShare());
        }
    }

    void requireRoomFor(UUID clubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(clubId).size()
                >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "That squad is full");
        }
    }

    LoanOfferResponse toResponse(LoanOffer offer, Player player, LoanRefusal refusal) {
        return new LoanOfferResponse(offer.getId(), player.getId(), player.getName(),
                offer.getStatus(), offer.getOwnerWageShare(), offer.isMayFaceOwner(),
                offer.getCounterOwnerWageShare(), offer.getCounterMayFaceOwner(), refusal);
    }
}
