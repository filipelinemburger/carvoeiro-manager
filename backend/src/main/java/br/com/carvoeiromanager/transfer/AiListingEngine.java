package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.watchlist.WatchlistNotifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Decides, per AI club, which players go up for transfer — deterministic and pure (no RNG):
 * every rule is a predicate over morale, appearances, club finances, and squad shape. Listings
 * persist across refreshes (a season rollover or a sale is what clears them); re-running this
 * for an unchanged squad is a no-op (idempotent).
 */
@Component
public class AiListingEngine {

    static final int MAX_ACTIVE_LISTINGS = 4;
    static final int MIN_GK_COVER = 2;
    static final int UNUSED_ROUND_THRESHOLD = 6;
    static final int MAX_FINANCIAL_LISTINGS = 2;
    static final int SURPLUS_FLOOR_SQUAD_SIZE = 17;

    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final ClubFinancialHealthEvaluator financialHealthEvaluator;
    private final TransferValuation transferValuation;
    private final WatchlistNotifier watchlistNotifier;

    public AiListingEngine(ClubRepository clubRepository, PlayerRepository playerRepository,
                           ClubFinancialHealthEvaluator financialHealthEvaluator,
                           TransferValuation transferValuation,
                           WatchlistNotifier watchlistNotifier) {
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.financialHealthEvaluator = financialHealthEvaluator;
        this.transferValuation = transferValuation;
        this.watchlistNotifier = watchlistNotifier;
    }

    /** Refreshes transfer listings for every AI club (id-sorted, for determinism) of the save. */
    @Transactional
    public void refreshListings(UUID saveGameId, UUID managedClubId, int currentRound, LocalDate eventDate) {
        List<Club> aiClubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .sorted(Comparator.comparing(Club::getId))
                .toList();
        for (Club club : aiClubs) {
            refreshClubListings(club, currentRound, saveGameId, eventDate);
        }
    }

    private void refreshClubListings(Club club, int currentRound, UUID saveGameId, LocalDate eventDate) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(club.getId());
        if (squad.size() <= SquadRules.MIN_SQUAD_SIZE) {
            return;
        }

        long gkCount = squad.stream().filter(p -> p.getPosition() == Position.GK).count();
        boolean desperate = financialHealthEvaluator.evaluate(club) == ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE;
        Player best = squad.stream()
                .max(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                .orElse(null);

        int alreadyListed = (int) squad.stream().filter(Player::isTransferListed).count();

        // Candidates: never a player the club only borrowed, never already listed, never a GK when
        // the club has <= 2 GKs, never the club's best player unless the club is DESPERATE.
        //
        // The loan exclusion is a plain predicate rather than SquadDeparture's throwing guard
        // because this engine SELECTS candidates — there is no user action here to reject, and a
        // borrowed player simply is not one of the club's to sell.
        List<Player> eligible = squad.stream()
                .filter(p -> !p.isOnLoan())
                .filter(p -> !p.isTransferListed())
                .filter(p -> !(p.getPosition() == Position.GK && gkCount <= MIN_GK_COVER))
                .filter(p -> desperate || best == null || !p.getId().equals(best.getId()))
                .sorted(Comparator.comparing(Player::getId))
                .toList();

        List<Player> newlyListed = new ArrayList<>();

        // 1) Per-player triggers, first match wins: PLAYER_REQUEST (morale bottomed out), then
        //    UNUSED (barely playing once the season is old enough to judge that).
        for (Player p : eligible) {
            if (alreadyListed + newlyListed.size() >= MAX_ACTIVE_LISTINGS) {
                break;
            }
            ListedReason reason = triggerFor(p, currentRound);
            if (reason != null) {
                p.listForTransfer(reason);
                newlyListed.add(p);
                watchlistNotifier.notifyListed(saveGameId, eventDate, p, club.getName(), reason.name());
            }
        }

        // 2) FINANCIAL: a DESPERATE club cashes in up to its 2 highest-value remaining players.
        if (desperate && alreadyListed + newlyListed.size() < MAX_ACTIVE_LISTINGS) {
            List<Player> remaining = eligible.stream()
                    .filter(p -> !newlyListed.contains(p))
                    .sorted(Comparator.comparing(
                            (Player p) -> transferValuation.marketValue(p.getOverall(), p.getAge())).reversed())
                    .toList();
            int financialCount = 0;
            for (Player p : remaining) {
                if (financialCount >= MAX_FINANCIAL_LISTINGS
                        || alreadyListed + newlyListed.size() >= MAX_ACTIVE_LISTINGS) {
                    break;
                }
                p.listForTransfer(ListedReason.FINANCIAL);
                newlyListed.add(p);
                watchlistNotifier.notifyListed(saveGameId, eventDate, p, club.getName(), ListedReason.FINANCIAL.name());
                financialCount++;
            }
        }

        // 3) SURPLUS floor: nothing else fired, but the squad is deep enough that the market
        //    should still see at least one player — list the weakest non-GK.
        if (alreadyListed + newlyListed.size() == 0 && squad.size() >= SURPLUS_FLOOR_SQUAD_SIZE) {
            eligible.stream()
                    .filter(p -> p.getPosition() != Position.GK)
                    .min(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                    .ifPresent(p -> {
                        p.listForTransfer(ListedReason.SURPLUS);
                        newlyListed.add(p);
                        watchlistNotifier.notifyListed(saveGameId, eventDate, p, club.getName(), ListedReason.SURPLUS.name());
                    });
        }

        if (!newlyListed.isEmpty()) {
            playerRepository.saveAll(newlyListed);
        }
    }

    /** PLAYER_REQUEST then UNUSED — the first matching per-player trigger, or null. */
    private ListedReason triggerFor(Player p, int currentRound) {
        if (p.getMorale() <= -3) {
            return ListedReason.PLAYER_REQUEST;
        }
        if (currentRound >= UNUSED_ROUND_THRESHOLD && p.getSeasonAppearances() * 3 < currentRound) {
            return ListedReason.UNUSED;
        }
        return null;
    }
}
