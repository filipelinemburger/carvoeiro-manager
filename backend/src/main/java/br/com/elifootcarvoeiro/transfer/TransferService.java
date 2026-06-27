package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    static final int MIN_SQUAD_SIZE = 14;
    static final int MAX_SQUAD_SIZE = 30;

    private static final double BID_CHANCE = 0.15;
    private static final int MAX_BIDS_PER_ROUND = 2;

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final TransferValuation transferValuation;
    private final TransferWindow transferWindow;
    private final TransferNegotiator transferNegotiator;
    private final TransferExecutor transferExecutor;

    public TransferService(SaveGameService saveGameService,
                           SaveGameRepository saveGameRepository,
                           PlayerRepository playerRepository,
                           ClubRepository clubRepository,
                           CompetitionRepository competitionRepository,
                           TransferOfferRepository transferOfferRepository,
                           TransferValuation transferValuation,
                           TransferWindow transferWindow,
                           TransferNegotiator transferNegotiator,
                           TransferExecutor transferExecutor) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.transferValuation = transferValuation;
        this.transferWindow = transferWindow;
        this.transferNegotiator = transferNegotiator;
        this.transferExecutor = transferExecutor;
    }

    public List<MarketPlayerResponse> getMarket(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        Map<UUID, String> clubNames = clubNames(saveGameId);
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !p.getClubId().equals(managedClubId))
                .map(p -> new MarketPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(), p.getClubId(),
                        clubNames.getOrDefault(p.getClubId(), ""),
                        transferValuation.marketValue(p.getOverall(), p.getAge()), p.getWage()))
                .sorted(Comparator.comparing(MarketPlayerResponse::value).reversed())
                .toList();
    }

    @Transactional
    public TransferOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId, BigDecimal amount) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        UUID managedClubId = save.getManagedClubId();
        requireWindowOpen(save);

        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> invalid("Player is not available for transfer"));
        if (player.getClubId().equals(managedClubId)) {
            throw invalid("Cannot buy a player you already own");
        }
        requireFunds(managedClubId, amount);
        requireSquadRoom(managedClubId);

        BigDecimal value = transferValuation.marketValue(player.getOverall(), player.getAge());
        TransferOffer offer = TransferOffer.create(saveGameId, playerId, managedClubId, player.getClubId(),
                TransferDirection.OUTGOING, amount, save.getCurrentRound());
        transferOfferRepository.save(offer);

        NegotiationResult result = transferNegotiator.respondToBuy(value, amount);
        switch (result.decision()) {
            case ACCEPT -> { transferExecutor.execute(save, offer, amount); offer.markAccepted(); }
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
        transferExecutor.execute(save, offer, fee);
        offer.markAccepted();
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

    public List<TransferOfferResponse> getOffers(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Unsolicited AI bids for managed players during an open window (deterministic, seeded). */
    @Transactional
    public void generateIncomingOffers(UUID saveGameId, int round) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        if (!transferWindow.isOpen(round, competition.getTotalRounds())) {
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

        List<Player> managedPlayers = playerRepository.findByClubId(managedClubId).stream()
                .sorted(Comparator.comparing(Player::getId)).toList();
        Random rng = new Random(saveGameId.getMostSignificantBits() ^ (long) round);
        int created = 0;
        for (Player player : managedPlayers) {
            if (created >= MAX_BIDS_PER_ROUND) {
                break;
            }
            if (alreadyBidOn.contains(player.getId())) {
                continue;
            }
            if (rng.nextDouble() < BID_CHANCE) {
                Club bidder = aiClubs.get(rng.nextInt(aiClubs.size()));
                BigDecimal bid = transferNegotiator.bidAmount(
                        transferValuation.marketValue(player.getOverall(), player.getAge()));
                transferOfferRepository.save(TransferOffer.create(saveGameId, player.getId(),
                        bidder.getId(), managedClubId, TransferDirection.INCOMING, bid, round));
                created++;
            }
        }
    }

    private void requireSellAllowed(UUID managedClubId, Player player) {
        List<Player> squad = playerRepository.findByClubId(managedClubId);
        if (squad.size() <= MIN_SQUAD_SIZE) {
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Squad would be too small", HttpStatus.UNPROCESSABLE_CONTENT);
        }
        if (player.getPosition() == Position.GK
                && squad.stream().filter(p -> p.getPosition() == Position.GK).count() <= 1) {
            throw new ApiException("SQUAD_TOO_SMALL",
                    "Cannot sell your last goalkeeper", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    // ---- shared helpers (also used by Task 7's selling methods) ----

    void requireWindowOpen(SaveGame save) {
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).get(0);
        if (!transferWindow.isOpen(save.getCurrentRound(), competition.getTotalRounds())) {
            throw new ApiException("TRANSFER_WINDOW_CLOSED",
                    "The transfer window is closed", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    TransferOffer requireOffer(UUID saveGameId, UUID offerId) {
        return transferOfferRepository.findById(offerId)
                .filter(o -> o.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException("TRANSFER_OFFER_NOT_FOUND",
                        "Transfer offer not found", HttpStatus.NOT_FOUND));
    }

    void requireFunds(UUID clubId, BigDecimal amount) {
        Club club = clubRepository.findById(clubId).orElseThrow();
        if (club.getBalance().compareTo(amount) < 0) {
            throw new ApiException("INSUFFICIENT_FUNDS",
                    "Not enough funds for this transfer", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubId(managedClubId).size() >= MAX_SQUAD_SIZE) {
            throw new ApiException("SQUAD_FULL",
                    "Squad is full", HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    ApiException invalid(String message) {
        return new ApiException("INVALID_TRANSFER", message, HttpStatus.UNPROCESSABLE_CONTENT);
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
                offer.getStatus().name(), offer.getRound());
    }
}
