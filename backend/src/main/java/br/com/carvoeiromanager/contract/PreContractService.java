package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bosman-style pre-contract negotiation (spec: 2026-07-19-pre-contract-offers-design.md).
 * Manager-facing flows: list eligible final-year rivals, offer terms (single counter
 * round), track agreements, withdraw before lock. The player's current club fights back
 * on TRANSFER days (processMarketDay, Task 4); deals execute at rollover (Task 6).
 */
@Service
public class PreContractService {

    static final List<PreContractStatus> ACTIVE = List.of(PreContractStatus.AGREED, PreContractStatus.LOCKED);

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final PreContractAgreementRepository agreementRepository;
    private final PreContractNegotiation negotiation;
    private final PreContractWindow preContractWindow;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;
    private final AiContractRenewalPolicy renewalPolicy;

    public PreContractService(SaveGameService saveGameService,
                              PlayerRepository playerRepository,
                              ClubRepository clubRepository,
                              PreContractAgreementRepository agreementRepository,
                              PreContractNegotiation negotiation,
                              PreContractWindow preContractWindow,
                              NewsItemRepository newsItemRepository,
                              ObjectMapper objectMapper,
                              AiContractRenewalPolicy renewalPolicy) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.agreementRepository = agreementRepository;
        this.negotiation = negotiation;
        this.preContractWindow = preContractWindow;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
        this.renewalPolicy = renewalPolicy;
    }

    // Distinct RNG streams per concern so pre-contract rolls never disturb the
    // incoming-bid RNG on a shared date (same convention as TransferService).
    private static final long RENEWAL_SALT = 0x5052454E45574CL;  // "PRENEWL"
    private static final long MARKET_SALT = 0x505245434D4BL;     // "PRECMK"

    /**
     * The TRANSFER-day pulse inside the pre-contract window, in spec order:
     * 1) AI clubs renew the final-year players they want to keep (seeded PER SEASON, so
     *    rerunning on every stop is idempotent — the same players are released each day);
     * 2) AGREED deals from earlier days resolve — the current club counter-renews or the
     *    deal locks (incoming deals lock unless the manager already renewed the player);
     * 3) at most one AI club agrees a poach with a managed final-year player.
     * Returns the day's events for the advance card / news feed.
     */
    @Transactional
    public List<PreContractEventResponse> processMarketDay(SaveGame save, LocalDate eventDate) {
        if (!preContractWindow.isOpen(eventDate)) {
            return List.of();
        }
        List<PreContractEventResponse> events = new ArrayList<>();
        runAiRenewalPass(save);
        resolvePendingAgreements(save, eventDate, events);
        aiPoach(save, eventDate, events);
        return events;
    }

    private void runAiRenewalPass(SaveGame save) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ (long) save.getSeasonYear() ^ RENEWAL_SALT);
        List<Club> clubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .filter(c -> !c.getId().equals(save.getManagedClubId()))
                .sorted(Comparator.comparing(Club::getId)).toList();
        for (Club club : clubs) {
            List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(club.getId()).stream()
                    .sorted(Comparator.comparing(Player::getId)).toList();
            int median = squadMedianOverall(squad);
            int remaining = squad.size();
            for (Player p : squad) {
                if (p.getContractYears() != 1) {
                    continue;
                }
                // The per-player decision consumes RNG in a fixed (id-sorted) order, so the
                // same seed yields the same renewals on every stop of the season.
                int years = renewalPolicy.renewYears(rng);
                if (renewalPolicy.shouldRelease(p.getAge(), p.getOverall(), median, remaining)) {
                    remaining--;                      // released: stays at 1, poachable
                } else {
                    p.setContractYears(1 + years);    // renewed: current season + 2-4 more
                    playerRepository.save(p);
                }
            }
        }
    }

    private void resolvePendingAgreements(SaveGame save, LocalDate eventDate,
                                          List<PreContractEventResponse> events) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ eventDate.toEpochDay() ^ MARKET_SALT);
        List<PreContractAgreement> pending = agreementRepository
                .findBySaveGameIdAndStatus(save.getId(), PreContractStatus.AGREED).stream()
                .filter(a -> a.getCreatedDate().isBefore(eventDate))
                .sorted(Comparator.comparing(PreContractAgreement::getId)).toList();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
        for (PreContractAgreement a : pending) {
            Optional<Player> playerOpt = playerRepository.findById(a.getPlayerId());
            if (playerOpt.isEmpty()) {
                a.cancel();
                agreementRepository.save(a);
                continue;
            }
            Player player = playerOpt.get();
            boolean outgoing = a.getToClubId().equals(save.getManagedClubId());
            String fromName = clubs.containsKey(a.getFromClubId()) ? clubs.get(a.getFromClubId()).getName() : "?";
            String toName = clubs.containsKey(a.getToClubId()) ? clubs.get(a.getToClubId()).getName() : "?";
            if (outgoing) {
                int fromRep = clubs.get(a.getFromClubId()).getReputation();
                int toRep = clubs.get(a.getToClubId()).getReputation();
                double keep = negotiation.keepProbability(fromRep, toRep, player.getAge());
                if (rng.nextDouble() < keep) {
                    a.cancel();
                    player.setContractYears(1 + renewalPolicy.renewYears(rng));
                    playerRepository.save(player);
                    events.add(new PreContractEventResponse("CANCELLED_OUT",
                            player.getName(), fromName, a.getWage(), a.getContractYears()));
                } else {
                    a.lock();
                    events.add(new PreContractEventResponse("LOCKED_OUT",
                            player.getName(), fromName, a.getWage(), a.getContractYears()));
                }
            } else {
                // Incoming threat: the manager's defense was a renewal before this stop
                // (which cancels the agreement in ContractService, Task 5). Still AGREED
                // here means no successful defense — the deal locks.
                a.lock();
                events.add(new PreContractEventResponse("LOCKED_IN",
                        player.getName(), toName, a.getWage(), a.getContractYears()));
            }
            agreementRepository.save(a);
        }
    }

    private void aiPoach(SaveGame save, LocalDate eventDate, List<PreContractEventResponse> events) {
        Random rng = new Random(save.getId().getMostSignificantBits()
                ^ eventDate.toEpochDay() ^ MARKET_SALT ^ 1L);
        if (rng.nextDouble() >= 0.5) {
            return; // no poach attempt today
        }
        Set<UUID> underAgreement = new HashSet<>(agreementRepository.findBySaveGameId(save.getId()).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()))
                .map(PreContractAgreement::getPlayerId).toList());
        List<Player> targets = playerRepository
                .findByClubIdAndYouthProspectFalse(save.getManagedClubId()).stream()
                .filter(p -> p.getContractYears() == 1 && p.getAge() <= 33)
                .filter(p -> !underAgreement.contains(p.getId()))
                .sorted(Comparator.comparing(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        List<Club> aiClubs = clubRepository.findBySaveGameId(save.getId()).stream()
                .filter(c -> !c.getId().equals(save.getManagedClubId()))
                .sorted(Comparator.comparing(Club::getReputation).reversed()
                        .thenComparing(Club::getId))
                .toList();
        for (Player target : targets) {
            for (Club club : aiClubs) {
                List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(club.getId());
                if (target.getOverall() >= squadMedianOverall(squad)) {
                    BigDecimal wage = negotiation.demand(target.getOverall(), target.getAge());
                    PreContractAgreement agreement = PreContractAgreement.create(save.getId(),
                            target.getId(), save.getManagedClubId(), club.getId(), wage,
                            renewalPolicy.renewYears(rng), eventDate);
                    agreementRepository.save(agreement);
                    events.add(new PreContractEventResponse("POACHED_IN", target.getName(),
                            club.getName(), wage, agreement.getContractYears()));
                    return; // spec: at most ONE new poach per TRANSFER day
                }
            }
        }
    }

    private int squadMedianOverall(List<Player> squad) {
        if (squad.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = squad.stream().map(Player::getOverall).sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    public List<PreContractEligibleResponse> getEligiblePlayers(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        if (!preContractWindow.isOpen(save.getCurrentDate())) {
            return List.of();
        }
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        List<UUID> underAgreement = agreementRepository.findBySaveGameId(saveGameId).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()))
                .map(PreContractAgreement::getPlayerId)
                .toList();
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !save.getManagedClubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .filter(p -> !underAgreement.contains(p.getId()))
                .map(p -> new PreContractEligibleResponse(p.getId(), p.getName(),
                        p.getPosition().name(), p.getAge(), p.getOverall(),
                        clubNames.getOrDefault(p.getClubId(), "?"),
                        negotiation.demand(p.getOverall(), p.getAge())))
                .sorted(Comparator.comparing(PreContractEligibleResponse::overall).reversed())
                .toList();
    }

    @Transactional
    public PreContractOfferResponse makeOffer(UUID saveGameId, UUID userId, UUID playerId,
                                              BigDecimal wage, int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireEligible(save, playerId);
        requireSquadRoom(save.getManagedClubId());
        if (contractYears < 1 || contractYears > 5 || wage == null || wage.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT,
                    "Contract length must be 1-5 years and wage must be positive");
        }
        BigDecimal demand = negotiation.demand(player.getOverall(), player.getAge());
        return switch (negotiation.classify(wage, demand)) {
            case AGREE -> agree(save, player, wage, contractYears);
            case COUNTER -> new PreContractOfferResponse("COUNTERED", demand, null);
            case REJECT -> throw new ApiException(ErrorCode.PLAYER_REJECTS_TERMS,
                    "Player rejects the approach outright; demands around " + demand);
        };
    }

    @Transactional
    public PreContractOfferResponse acceptCounter(UUID saveGameId, UUID userId, UUID playerId,
                                                  int contractYears) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = requireEligible(save, playerId);
        requireSquadRoom(save.getManagedClubId());
        if (contractYears < 1 || contractYears > 5) {
            throw new ApiException(ErrorCode.INVALID_CONTRACT, "Contract length must be 1-5 years");
        }
        // Accepting the counter means paying exactly the demanded wage.
        BigDecimal demand = negotiation.demand(player.getOverall(), player.getAge());
        return agree(save, player, demand, contractYears);
    }

    public List<PreContractAgreementResponse> getAgreements(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        return agreementRepository.findBySaveGameId(saveGameId).stream()
                .map(a -> {
                    Player p = players.get(a.getPlayerId());
                    return new PreContractAgreementResponse(a.getId(), a.getPlayerId(),
                            p != null ? p.getName() : "?",
                            p != null ? p.getPosition().name() : "?",
                            p != null ? p.getAge() : 0,
                            p != null ? p.getOverall() : 0,
                            clubNames.getOrDefault(a.getFromClubId(), "?"),
                            clubNames.getOrDefault(a.getToClubId(), "?"),
                            a.getWage(), a.getContractYears(), a.getStatus().name(),
                            a.getFromClubId().equals(save.getManagedClubId()));
                })
                .toList();
    }

    @Transactional
    public void withdraw(UUID saveGameId, UUID userId, UUID agreementId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        PreContractAgreement agreement = agreementRepository.findById(agreementId)
                .filter(a -> a.getSaveGameId().equals(saveGameId))
                .filter(a -> a.getToClubId().equals(save.getManagedClubId()))
                .filter(a -> a.getStatus() == PreContractStatus.AGREED)
                .orElseThrow(() -> new ApiException(ErrorCode.PRE_CONTRACT_NOT_FOUND,
                        "No withdrawable pre-contract agreement found"));
        agreementRepository.delete(agreement);
    }

    // ---- shared internals ----

    private PreContractOfferResponse agree(SaveGame save, Player player,
                                           BigDecimal wage, int contractYears) {
        PreContractAgreement agreement = PreContractAgreement.create(save.getId(), player.getId(),
                player.getClubId(), save.getManagedClubId(), wage, contractYears,
                save.getCurrentDate());
        agreementRepository.save(agreement);
        String fromName = clubRepository.findById(player.getClubId()).map(Club::getName).orElse("?");
        persistPreContractNews(save, new PreContractEventResponse(
                "AGREED_OUT", player.getName(), fromName, wage, contractYears));
        PreContractAgreementResponse dto = new PreContractAgreementResponse(agreement.getId(),
                player.getId(), player.getName(), player.getPosition().name(), player.getAge(),
                player.getOverall(), fromName,
                clubRepository.findById(save.getManagedClubId()).map(Club::getName).orElse("?"),
                wage, contractYears, agreement.getStatus().name(), false);
        return new PreContractOfferResponse("AGREED", null, dto);
    }

    private Player requireEligible(SaveGame save, UUID playerId) {
        if (!preContractWindow.isOpen(save.getCurrentDate())) {
            throw new ApiException(ErrorCode.PRE_CONTRACT_WINDOW_CLOSED,
                    "Pre-contract talks only open on July 15, six months before contracts lapse");
        }
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(save.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found"));
        boolean rival = !player.isFreeAgent() && !save.getManagedClubId().equals(player.getClubId());
        if (player.isYouthProspect() || !rival || player.getContractYears() != 1) {
            throw new ApiException(ErrorCode.PLAYER_NOT_PRE_CONTRACT_ELIGIBLE,
                    "Only rival players in their final contract year can be approached");
        }
        if (!agreementRepository.findBySaveGameIdAndPlayerIdAndStatusIn(
                save.getId(), playerId, ACTIVE).isEmpty()) {
            throw new ApiException(ErrorCode.PRE_CONTRACT_ALREADY_EXISTS,
                    "This player already has an active pre-contract agreement");
        }
        return player;
    }

    private void requireSquadRoom(UUID managedClubId) {
        if (playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).size()
                >= SquadRules.MAX_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_FULL, "Squad is full");
        }
    }

    /**
     * Persist a PRECONTRACT news item so the feed records the deal. The payload is an
     * AdvanceResponse-shaped map (the frontend renders news payloads with the same card
     * it uses for advances); only type/date/preContractEvents are meaningful.
     */
    void persistPreContractNews(SaveGame save, PreContractEventResponse event) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", "PRECONTRACT",
                    "date", save.getCurrentDate().toString(),
                    "preContractEvents", List.of(event)));
            int seq = newsItemRepository.countBySaveGameId(save.getId()) + 1;
            newsItemRepository.save(NewsItem.create(save.getId(), seq, save.getCurrentDate(),
                    "PRECONTRACT", payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize pre-contract news item", e);
        }
    }
}
