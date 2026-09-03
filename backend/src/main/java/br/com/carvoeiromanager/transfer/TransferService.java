package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.common.SquadDeparture;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.contract.ContractValuation;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.watchlist.WatchlistNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transfer market orchestration: market browse, outgoing offers (synchronous AI
 * negotiation), counter acceptance, and withdrawal. Selling (incoming bids) is added
 * in the same class by Task 7. All mutations are window-/budget-/squad-guarded.
 */
@Service
public class TransferService {

    private static final double BID_CHANCE = 0.15;
    private static final double LISTED_BID_CHANCE = 0.45;
    private static final int MAX_BIDS_PER_ROUND = 3;

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final TransferValuation transferValuation;
    private final TransferWindow transferWindow;
    private final TransferNegotiator transferNegotiator;
    private final TransferExecutor transferExecutor;
    private final ContractValuation contractValuation;
    private final AiListingEngine aiListingEngine;
    private final AiTransferEngine aiTransferEngine;
    private final SellerContextFactory sellerContextFactory;
    private final WatchlistNotifier watchlistNotifier;
    private final SquadDeparture squadDeparture;

    public TransferService(SaveGameService saveGameService,
                           SaveGameRepository saveGameRepository,
                           PlayerRepository playerRepository,
                           ClubRepository clubRepository,
                           TransferOfferRepository transferOfferRepository,
                           TransferValuation transferValuation,
                           TransferWindow transferWindow,
                           TransferNegotiator transferNegotiator,
                           TransferExecutor transferExecutor,
                           ContractValuation contractValuation,
                           AiListingEngine aiListingEngine,
                           AiTransferEngine aiTransferEngine,
                           SellerContextFactory sellerContextFactory,
                           WatchlistNotifier watchlistNotifier,
                           SquadDeparture squadDeparture) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.transferValuation = transferValuation;
        this.transferWindow = transferWindow;
        this.transferNegotiator = transferNegotiator;
        this.transferExecutor = transferExecutor;
        this.contractValuation = contractValuation;
        this.aiListingEngine = aiListingEngine;
        this.aiTransferEngine = aiTransferEngine;
        this.sellerContextFactory = sellerContextFactory;
        this.watchlistNotifier = watchlistNotifier;
        this.squadDeparture = squadDeparture;
    }

    /** Convenience overload for callers that don't need filtering/paging (e.g. internal checks). */
    public List<MarketPlayerResponse> getMarket(UUID saveGameId, UUID userId) {
        return unpagedMarket(saveGameId, userId, MarketFilter.empty());
    }

    /**
     * The transfer market, filtered and paged. Filtering/sorting/paging all happen in memory —
     * a save has at most a few hundred players, so there's no need for SQL-level pagination, and
     * {@code value} is derived (via {@link TransferValuation}), not a stored column to query on.
     * Sorted by value descending with a playerId tiebreak so paging stays stable across requests.
     */
    public PageResponse<MarketPlayerResponse> getMarket(UUID saveGameId, UUID userId, MarketFilter filter,
                                                         int page, int size) {
        List<MarketPlayerResponse> all = unpagedMarket(saveGameId, userId, filter);
        return PageResponse.of(all, page, size);
    }

    private List<MarketPlayerResponse> unpagedMarket(UUID saveGameId, UUID userId, MarketFilter filter) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        Map<UUID, String> clubNames = clubNames(saveGameId);
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.getClubId().equals(managedClubId))
                .filter(Player::isTransferListed)
                .map(p -> new MarketPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(), p.getClubId(),
                        clubNames.getOrDefault(p.getClubId(), ""),
                        transferValuation.marketValue(p.getOverall(), p.getAge()), p.getWage(),
                        contractValuation.wageDemand(p.getOverall(), p.getAge()),
                        p.getListedReason() != null ? p.getListedReason().name() : null,
                        p.getContractYears(), p.isOnLoan()))
                .filter(filter::matches)
                .sorted(Comparator.comparing(MarketPlayerResponse::value).reversed()
                        .thenComparing(MarketPlayerResponse::playerId))
                .toList();
    }

    /**
     * Manually lists one of the managed club's own (non-youth) players for transfer. Allowed
     * even with the window closed — listing is a squad-management decision, not a negotiation.
     */
    @Transactional
    public TransferListingResponse listPlayer(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedNonYouthPlayer(saveGameId, save.getManagedClubId(), playerId);
        player.listForTransfer(ListedReason.OWNER);
        playerRepository.save(player);
        return toListingResponse(player);
    }

    /** Pulls one of the managed club's own players off the transfer list. */
    @Transactional
    public TransferListingResponse unlistPlayer(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireManagedNonYouthPlayer(saveGameId, save.getManagedClubId(), playerId);
        player.unlist();
        playerRepository.save(player);
        return toListingResponse(player);
    }

    @Transactional
    public TransferOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId,
                                           BigDecimal amount, BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> invalid("Player is not available for transfer"));
        if (player.getClubId() == null) {
            throw invalid("Free agents are signed, not bought");
        }
        if (player.getClubId().equals(managedClubId)) {
            throw invalid("Cannot buy a player you already own");
        }
        requireValidTerms(wage, contractYears);
        requireFunds(managedClubId, amount);
        requireSquadRoom(managedClubId);

        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());
        TransferOffer offer = TransferOffer.create(saveGameId, playerId, managedClubId, player.getClubId(),
                TransferDirection.OUTGOING, amount, save.getCurrentRound(), wage, contractYears);
        transferOfferRepository.save(offer);

        Club sellerClub = clubRepository.findById(player.getClubId()).orElseThrow();
        SellerContext sellerContext = sellerContextFactory.build(player, sellerClub);
        NegotiationResult result = transferNegotiator.respondToBuy(value, amount, sellerContext);
        switch (result.decision()) {
            case ACCEPT -> completeBuy(save, offer, player, amount);
            case COUNTER -> offer.markCountered(result.counterAmount());
            case REJECT -> offer.markRejected();
        }
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    @Transactional
    public TransferOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID offerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.OUTGOING
                || offer.getStatus() != TransferOfferStatus.COUNTERED) {
            throw invalid("No counter to accept on this offer");
        }
        requireWindowOpen(save);
        BigDecimal fee = offer.getCounterAmount();
        requireFunds(save.getManagedClubId(), fee);
        requireSquadRoom(save.getManagedClubId());
        Player player = playerRepository.findById(offer.getPlayerId())
                .orElseThrow(() -> invalid("Player no longer exists"));
        completeBuy(save, offer, player, fee);
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    @Transactional
    public TransferOfferResponse withdrawOffer(UUID saveGameId, UUID userId, UUID offerId) {
        saveGameService.requireOwned(saveGameId, userId);
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.OUTGOING
                || (offer.getStatus() != TransferOfferStatus.PENDING
                    && offer.getStatus() != TransferOfferStatus.COUNTERED)) {
            throw invalid("This offer cannot be withdrawn");
        }
        offer.markWithdrawn();
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    @Transactional
    public TransferOfferResponse respondToIncoming(UUID saveGameId, UUID userId, UUID offerId,
                                                   String action, BigDecimal counterAmount) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        TransferOffer offer = requireOffer(saveGameId, offerId);
        if (offer.getDirection() != TransferDirection.INCOMING
                || !offer.getToClubId().equals(managedClubId)
                || (offer.getStatus() != TransferOfferStatus.PENDING
                    && offer.getStatus() != TransferOfferStatus.COUNTERED)) {
            throw invalid("This incoming offer cannot be answered");
        }
        requireWindowOpen(save);
        squadDeparture.requireNoLiveMatch(saveGameId);
        Player player = playerRepository.findById(offer.getPlayerId()).orElseThrow(() -> invalid("Player no longer exists"));
        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());

        switch (action) {
            case "ACCEPT" -> {
                requireSellAllowed(managedClubId, player);
                transferExecutor.execute(save, offer, offer.getAmount());
                offer.markAccepted();
            }
            case "REJECT" -> offer.markRejected();
            case "COUNTER" -> {
                if (counterAmount == null || counterAmount.signum() <= 0) {
                    throw invalid("Counter amount must be positive");
                }
                offer.markCountered(counterAmount);
                if (transferNegotiator.acceptsSellCounter(value, counterAmount)) {
                    requireSellAllowed(managedClubId, player);
                    transferExecutor.execute(save, offer, counterAmount);
                    offer.markAccepted();
                } else {
                    offer.markRejected();
                }
            }
            default -> throw invalid("Unknown action: " + action);
        }
        transferOfferRepository.save(offer);
        return toResponse(offer);
    }

    /**
     * The user's own active negotiations only. AI-to-AI trades (direction AI) are always
     * persisted ACCEPTED — never PENDING/COUNTERED — so the status filter alone keeps them out
     * of this inbox without needing an explicit direction check.
     */
    public List<TransferOfferResponse> getOffers(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Full market-day orchestration for a save: refreshes AI listings, lets AI clubs trade
     * amongst themselves (the "living market"), then rolls unsolicited bids for the managed
     * club. Window-gated — a closed window does none of it. Called from
     * {@code SeasonAdvanceService} on each TRANSFER calendar event and once, day one, from
     * {@code SaveGameService.createCareer}.
     */
    @Transactional
    public List<CompletedTransferResponse> processMarketDay(UUID saveGameId, LocalDate eventDate) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        if (!transferWindow.isOpen(eventDate)) {
            return List.of();
        }
        aiListingEngine.refreshListings(saveGameId, save.getManagedClubId(), save.getCurrentRound(), eventDate);
        List<CompletedTransferResponse> transfers = aiTransferEngine.run(save, eventDate);
        generateIncomingOffers(saveGameId, eventDate);
        return transfers;
    }

    /** Unsolicited AI bids for managed players during an open window (deterministic, seeded). */
    @Transactional
    public void generateIncomingOffers(UUID saveGameId, LocalDate eventDate) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        if (!transferWindow.isOpen(eventDate)) {
            return;
        }
        UUID managedClubId = save.getManagedClubId();
        List<Club> aiClubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .sorted(Comparator.comparing(Club::getId)).toList();
        if (aiClubs.isEmpty()) {
            return;
        }
        Set<UUID> alreadyBidOn = transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .filter(o -> o.getDirection() == TransferDirection.INCOMING)
                .map(TransferOffer::getPlayerId)
                .collect(Collectors.toSet());

        // Listed players come first: they're more likely to draw a (better) bid. Borrowed players
        // are excluded outright — the managed club cannot sell one, so a bid for him could only
        // ever be refused, and offering it would be an invitation to a deal that does not exist.
        List<Player> managedPlayers = playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).stream()
                .filter(p -> !p.isOnLoan())
                .sorted(Comparator.comparing((Player p) -> !p.isTransferListed())
                        .thenComparing(Player::getId))
                .toList();
        Random rng = new Random(saveGameId.getMostSignificantBits() ^ eventDate.toEpochDay());
        int created = 0;
        for (Player player : managedPlayers) {
            if (created >= MAX_BIDS_PER_ROUND) {
                break;
            }
            if (alreadyBidOn.contains(player.getId())) {
                continue;
            }
            boolean listed = player.isTransferListed();
            double chance = listed ? LISTED_BID_CHANCE : BID_CHANCE;
            if (rng.nextDouble() < chance) {
                Club bidder = aiClubs.get(rng.nextInt(aiClubs.size()));
                BigDecimal bid = transferNegotiator.bidAmount(
                        transferValuation.marketValue(player.getOverall(), player.getAge()), listed);
                transferOfferRepository.save(TransferOffer.create(saveGameId, player.getId(),
                        bidder.getId(), managedClubId, TransferDirection.INCOMING, bid,
                        save.getCurrentRound(), null, null));
                watchlistNotifier.notifyOfferReceived(saveGameId, eventDate, player,
                        bidder.getName(), bid);
                created++;
            }
        }
    }

    /** Completes a buy iff the player accepts the offered wage, else the deal falls through. */
    private void completeBuy(SaveGame save, TransferOffer offer, Player player, BigDecimal fee) {
        if (!contractValuation.acceptsWage(player.getOverall(), player.getAge(), offer.getWage())) {
            offer.markRejected(); // personal terms not met; no money moves, player stays
            return;
        }
        transferExecutor.execute(save, offer, fee);
        offer.markAccepted();
    }

    private void requireValidTerms(BigDecimal wage, int contractYears) {
        if (!contractValuation.isValidLength(contractYears) || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
    }

    private Player requireManagedNonYouthPlayer(UUID saveGameId, UUID managedClubId, UUID playerId) {
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> invalid("Player not found"));
        if (!managedClubId.equals(player.getClubId())) {
            throw invalid("Only your own players can be listed for transfer");
        }
        if (player.isYouthProspect()) {
            throw invalid("Youth prospects cannot be listed for transfer");
        }
        // He plays here, but club_id does not say he is ours — a borrowed player must not be put
        // on the market by the club that only borrowed him.
        squadDeparture.requireNotOnLoan(player);
        return player;
    }

    private TransferListingResponse toListingResponse(Player player) {
        return new TransferListingResponse(player.getId(), player.isTransferListed(),
                player.getListedReason() != null ? player.getListedReason().name() : null);
    }

    private void requireSellAllowed(UUID managedClubId, Player player) {
        squadDeparture.requireDepartureAllowed(squadDeparture.seniorSquad(managedClubId), player);
    }

    // ---- shared helpers (also used by Task 7's selling methods) ----

    void requireWindowOpen(SaveGame save) {
        if (!transferWindow.isOpen(save.getCurrentDate())) {
            throw new ApiException(ErrorCode.TRANSFER_WINDOW_CLOSED, "The transfer window is closed");
        }
    }

    TransferOffer requireOffer(UUID saveGameId, UUID offerId) {
        return transferOfferRepository.findById(offerId)
                .filter(o -> o.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_OFFER_NOT_FOUND, "Transfer offer not found"));
    }

    void requireFunds(UUID clubId, BigDecimal amount) {
        Club club = clubRepository.findById(clubId).orElseThrow();
        if (club.getBalance().compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Not enough funds for this transfer");
        }
    }

    void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size() >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }

    ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_TRANSFER, message);
    }

    Map<UUID, String> clubNames(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
    }

    TransferOfferResponse toResponse(TransferOffer offer) {
        String playerName = playerRepository.findById(offer.getPlayerId())
                .map(Player::getName).orElse("");
        String fromName = clubRepository.findById(offer.getFromClubId()).map(Club::getName).orElse("");
        String toName = clubRepository.findById(offer.getToClubId()).map(Club::getName).orElse("");
        return new TransferOfferResponse(offer.getId(), offer.getPlayerId(), playerName,
                offer.getFromClubId(), fromName, offer.getToClubId(), toName,
                offer.getDirection().name(), offer.getAmount(), offer.getCounterAmount(),
                offer.getStatus().name(), offer.getRound(), offer.getWage(), offer.getContractYears());
    }
}
