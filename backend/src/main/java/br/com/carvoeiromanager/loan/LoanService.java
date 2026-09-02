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
import br.com.carvoeiromanager.transfer.NegotiationDecision;
import br.com.carvoeiromanager.transfer.SellerContext;
import br.com.carvoeiromanager.transfer.SellerContextFactory;
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

    public LoanService(SaveGameService saveGameService, PlayerRepository playerRepository,
                       ClubRepository clubRepository, LoanOfferRepository loanOfferRepository,
                       LoanPlausibility loanPlausibility, LoanNegotiator loanNegotiator,
                       SellerContextFactory sellerContextFactory, SquadDeparture squadDeparture) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.loanOfferRepository = loanOfferRepository;
        this.loanPlausibility = loanPlausibility;
        this.loanNegotiator = loanNegotiator;
        this.sellerContextFactory = sellerContextFactory;
        this.squadDeparture = squadDeparture;
    }

    /** The manager lends one of his own players to an AI club. */
    @Transactional
    public LoanOfferResponse offerLoanOut(UUID saveGameId, UUID userId, UUID playerId,
                                          UUID borrowerClubId, int ownerWageShare,
                                          boolean mayFaceOwner) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();

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
        requireRoomFor(managedClubId);

        Club owner = clubRepository.findById(player.getClubId()).orElseThrow();
        Club borrower = clubRepository.findById(managedClubId).orElseThrow();

        return negotiate(save, player, owner, borrower,
                new LoanTerms(ownerWageShare, mayFaceOwner), true);
    }

    /**
     * The shared core: decide, then either execute or record what the AI said. {@code aiIsOwner}
     * says which side the AI sits on, which is the only thing that differs between directions.
     * The wage-share bound is enforced here rather than in each direction's entry point, so
     * every caller — Task 7's {@code offerLoanIn} included — gets it for free and nothing can
     * persist a share the finance split could not honour.
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
        if (result.decision() == NegotiationDecision.COUNTER) {
            offer.markCountered(result.counter().ownerWageShare(), result.counter().mayFaceOwner());
            loanOfferRepository.save(offer);
            return toResponse(offer, player, LoanRefusal.TERMS_REJECTED);
        }

        execute(save, player, borrower.getId(), offered);
        offer.markAccepted();
        loanOfferRepository.save(offer);
        return toResponse(offer, player, null);
    }

    /**
     * Moves the player and settles everything leaving the squad implies. The offer rejection is
     * not optional: a live bid that outlived the loan would meet a sell guard that now passes
     * trivially, because the player is no longer in the squad it counts.
     */
    void execute(SaveGame save, Player player, UUID borrowerClubId, LoanTerms terms) {
        UUID formerClubId = player.getClubId();
        player.loanTo(borrowerClubId, terms.ownerWageShare(), terms.mayFaceOwner());
        playerRepository.save(player);
        squadDeparture.cleanManagedLineup(save.getId(), formerClubId, player.getId());
        squadDeparture.rejectOpenOffersFor(save.getId(), player.getId());
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
