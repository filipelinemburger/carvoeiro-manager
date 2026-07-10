package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The "living market": AI clubs trade listed players amongst themselves, independent of the
 * managed club. Runs once per TRANSFER calendar event, seeded so the same save+date+state always
 * reproduces the same trades.
 *
 * <p>A buyer (AI club, squad below {@link SquadRules#MAX_SQUAD_SIZE}, financial health not
 * DESPERATE) considers the listed-player pool (deepest squads' surplus, priced 0.90x value) it
 * can both afford (price &lt;= 40% of balance) and needs (position count below the floor, or an
 * overall upgrade over the position's average). The seller must stay above the squad-size floor
 * and keep a goalkeeper. Every buyer gets at most one shot per run, capped at
 * {@value #MAX_TRANSFERS_PER_EVENT} total trades; a player moves at most once.
 */
@Component
public class AiTransferEngine {

    static final int MAX_TRANSFERS_PER_EVENT = 2;
    static final BigDecimal PRICE_FACTOR = new BigDecimal("0.90");
    static final BigDecimal AFFORDABILITY_FACTOR = new BigDecimal("0.40");
    static final int UPGRADE_MARGIN = 3;

    private static final Map<Position, Integer> NEED_THRESHOLD = new EnumMap<>(Position.class);
    static {
        NEED_THRESHOLD.put(Position.GK, 2);
        NEED_THRESHOLD.put(Position.DEF, 6);
        NEED_THRESHOLD.put(Position.MID, 6);
        NEED_THRESHOLD.put(Position.ATT, 4);
    }

    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final TransferOfferRepository transferOfferRepository;
    private final TransferValuation transferValuation;
    private final TransferExecutor transferExecutor;
    private final ClubFinancialHealthEvaluator financialHealthEvaluator;

    public AiTransferEngine(ClubRepository clubRepository, PlayerRepository playerRepository,
                            TransferOfferRepository transferOfferRepository,
                            TransferValuation transferValuation, TransferExecutor transferExecutor,
                            ClubFinancialHealthEvaluator financialHealthEvaluator) {
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.transferOfferRepository = transferOfferRepository;
        this.transferValuation = transferValuation;
        this.transferExecutor = transferExecutor;
        this.financialHealthEvaluator = financialHealthEvaluator;
    }

    /** Runs one market-day pass of AI-to-AI trading; returns every trade completed. */
    @Transactional
    public List<CompletedTransferResponse> run(SaveGame save, LocalDate eventDate) {
        final UUID saveGameId = save.getId();
        final UUID managedClubId = save.getManagedClubId();
        long seed = save.getId().getMostSignificantBits() ^ eventDate.toEpochDay() ^ 0x9E3779B97F4A7C15L;
        Random rng = new Random(seed);

        List<Club> aiClubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .toList();
        if (aiClubs.isEmpty()) {
            return List.of();
        }

        // Mutable per-club state, updated as trades execute within this run.
        Map<UUID, List<Player>> squadByClub = new HashMap<>();
        Map<UUID, BigDecimal> balanceByClub = new HashMap<>();
        for (Club club : aiClubs) {
            squadByClub.put(club.getId(), new ArrayList<>(playerRepository.findByClubIdAndYouthProspectFalse(club.getId())));
            balanceByClub.put(club.getId(), club.getBalance());
        }

        Set<UUID> lockedPlayerIds = transferOfferRepository.findBySaveGameIdAndStatusIn(saveGameId,
                        List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED)).stream()
                .map(TransferOffer::getPlayerId)
                .collect(Collectors.toSet());

        Map<UUID, BigDecimal> valueByPlayer = new HashMap<>();
        List<Player> pool = aiClubs.stream()
                .flatMap(club -> squadByClub.get(club.getId()).stream())
                .filter(Player::isTransferListed)
                .filter(p -> !lockedPlayerIds.contains(p.getId()))
                .peek(p -> valueByPlayer.put(p.getId(), transferValuation.marketValue(p.getOverall(), p.getAge())))
                .sorted(Comparator.comparing((Player p) -> valueByPlayer.get(p.getId())).reversed()
                        .thenComparing(Player::getId))
                .toList();

        List<Club> buyers = aiClubs.stream()
                .filter(club -> squadByClub.get(club.getId()).size() < SquadRules.MAX_SQUAD_SIZE)
                .filter(club -> financialHealthEvaluator.evaluate(club) != ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE)
                .sorted(Comparator.comparing(Club::getBalance).reversed().thenComparing(Club::getId))
                .toList();

        Set<UUID> movedPlayerIds = new HashSet<>();
        List<CompletedTransferResponse> completed = new ArrayList<>();

        for (Club buyer : buyers) {
            if (completed.size() >= MAX_TRANSFERS_PER_EVENT) {
                break;
            }
            List<Player> candidates = pool.stream()
                    .filter(p -> !movedPlayerIds.contains(p.getId()))
                    .filter(p -> !p.getClubId().equals(buyer.getId()))
                    .filter(p -> needsPlayer(buyer, p, squadByClub))
                    .filter(p -> affordable(valueByPlayer.get(p.getId()), balanceByClub.get(buyer.getId())))
                    .filter(p -> sellerCanLet(p, squadByClub.get(p.getClubId())))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            Player chosen = candidates.get(rng.nextInt(candidates.size()));
            BigDecimal fee = price(valueByPlayer.get(chosen.getId()));
            UUID sellerId = chosen.getClubId();

            transferExecutor.execute(save, chosen.getId(), buyer.getId(), sellerId, fee, null, null);
            persistOffer(saveGameId, chosen.getId(), buyer.getId(), sellerId, fee, save.getCurrentRound());

            String buyerName = buyer.getName();
            String sellerName = clubName(aiClubs, sellerId);
            completed.add(new CompletedTransferResponse(chosen.getName(), sellerName, buyerName, fee));

            movedPlayerIds.add(chosen.getId());
            squadByClub.get(sellerId).removeIf(p -> p.getId().equals(chosen.getId()));
            squadByClub.get(buyer.getId()).add(chosen);
            balanceByClub.merge(buyer.getId(), fee, (bal, f) -> bal.subtract(f));
            balanceByClub.merge(sellerId, fee, BigDecimal::add);
        }

        return completed;
    }

    private String clubName(List<Club> clubs, UUID clubId) {
        return clubs.stream().filter(c -> c.getId().equals(clubId)).findFirst()
                .map(Club::getName).orElse("");
    }

    private void persistOffer(UUID saveGameId, UUID playerId, UUID buyerId, UUID sellerId,
                              BigDecimal fee, int round) {
        TransferOffer offer = TransferOffer.create(saveGameId, playerId, buyerId, sellerId,
                TransferDirection.AI, fee, round, null, null);
        offer.markAccepted();
        transferOfferRepository.save(offer);
    }

    private boolean affordable(BigDecimal value, BigDecimal balance) {
        BigDecimal price = price(value);
        return price.compareTo(balance.multiply(AFFORDABILITY_FACTOR)) <= 0;
    }

    private BigDecimal price(BigDecimal value) {
        return value.multiply(PRICE_FACTOR).setScale(2, RoundingMode.HALF_UP);
    }

    /** Below the position floor, or a clear upgrade (overall >= position average + 3). */
    private boolean needsPlayer(Club buyer, Player candidate, Map<UUID, List<Player>> squadByClub) {
        List<Player> buyerSquad = squadByClub.get(buyer.getId());
        Position position = candidate.getPosition();
        long count = buyerSquad.stream().filter(p -> p.getPosition() == position).count();
        if (count < NEED_THRESHOLD.get(position)) {
            return true;
        }
        OptionalDouble avg = buyerSquad.stream().filter(p -> p.getPosition() == position)
                .mapToInt(Player::getOverall).average();
        return avg.isPresent() && candidate.getOverall() >= avg.getAsDouble() + UPGRADE_MARGIN;
    }

    /** The seller keeps a squad of at least MIN_SQUAD_SIZE and at least one goalkeeper. */
    private boolean sellerCanLet(Player candidate, List<Player> sellerSquad) {
        if (sellerSquad.size() - 1 < TransferService.MIN_SQUAD_SIZE) {
            return false;
        }
        if (candidate.getPosition() == Position.GK) {
            long gkCount = sellerSquad.stream().filter(p -> p.getPosition() == Position.GK).count();
            return gkCount > 1;
        }
        return true;
    }
}
