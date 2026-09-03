package br.com.carvoeiromanager.common;

import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.LiveMatchRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.transfer.TransferOffer;
import br.com.carvoeiromanager.transfer.TransferOfferRepository;
import br.com.carvoeiromanager.transfer.TransferOfferStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The rules every way out of the managed squad shares — selling a player, terminating his contract
 * and lending him out: when a departure is legal at all, and what must be cleaned up once it
 * happens. Kept in one place so the paths cannot drift apart.
 */
@Component
public class SquadDeparture {

    /** A negotiation still live enough to turn into a sale if it is left alone. */
    private static final List<TransferOfferStatus> OPEN_STATUSES =
            List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED);

    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final LiveMatchRepository liveMatchRepository;
    private final TransferOfferRepository transferOfferRepository;

    public SquadDeparture(PlayerRepository playerRepository,
                          LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository,
                          LiveMatchRepository liveMatchRepository,
                          TransferOfferRepository transferOfferRepository) {
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.liveMatchRepository = liveMatchRepository;
        this.transferOfferRepository = transferOfferRepository;
    }

    /** The professional squad: youth prospects never count toward the departure rules. */
    public List<Player> seniorSquad(UUID clubId) {
        return playerRepository.findByClubIdAndYouthProspectFalse(clubId);
    }

    /**
     * Refuses to dispose of a player the club does not own. {@code club_id} means <em>where he
     * plays</em>, so a borrowed player sits in the squad indistinguishably from an owned one and
     * passes every "is he mine?" lookup that reads {@code club_id} alone — {@code owner_club_id}
     * is the only thing that says who he belongs to.
     *
     * <p>Without this, the club that merely borrowed him could terminate his contract (leaving the
     * row simultaneously a free agent and on loan — the invariant the loans spec calls load-bearing)
     * or sell him to a third club (leaving a stale owner still paying his wage share forever).
     * Neither is recoverable, and both are reachable in normal play.
     */
    public void requireNotOnLoan(Player player) {
        if (player.isOnLoan()) {
            throw new ApiException(ErrorCode.PLAYER_ALREADY_ON_LOAN,
                    "This player is on loan from another club; you cannot dispose of him");
        }
    }

    /**
     * Refuses a departure that would leave the club unable to field a team: below the squad
     * floor, or without a goalkeeper. Also refuses a departure the club has no right to make at
     * all — see {@link #requireNotOnLoan} — so every exit inherits the ownership check rather
     * than each one remembering it.
     */
    public void requireDepartureAllowed(List<Player> seniorSquad, Player leaving) {
        requireNotOnLoan(leaving);
        if (seniorSquad.size() <= SquadRules.MIN_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Squad would be too small");
        }
        if (leaving.getPosition() == Position.GK
                && seniorSquad.stream().filter(p -> p.getPosition() == Position.GK).count() <= 1) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Cannot release your last goalkeeper");
        }
    }

    /**
     * Refuses a departure while a match is being played: the live match holds its own serialised
     * XI, so a player who left mid-match would still be on the pitch and would still collect the
     * match's effects afterwards.
     */
    public void requireNoLiveMatch(UUID saveGameId) {
        if (liveMatchRepository.findBySaveGameId(saveGameId).isPresent()) {
            throw new ApiException(ErrorCode.LIVE_MATCH_IN_PROGRESS,
                    "Finish the live match before completing transfers");
        }
    }

    /**
     * Removes a departing player from the managed lineup: the lineup_players row and any
     * set-piece-taker column pointing at him (no-cascade FK safety).
     */
    public void cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId) {
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupIdAndPlayerId(lineup.getId(), playerId);
            boolean takerChanged = false;
            if (playerId.equals(lineup.getPenaltyTakerId())) { lineup.setPenaltyTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getFreekickTakerId())) { lineup.setFreekickTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getCornerTakerId())) { lineup.setCornerTakerId(null); takerChanged = true; }
            if (takerChanged) {
                lineupRepository.save(lineup);
            }
        });
    }

    /**
     * Kills every negotiation still open on a departing player. Offers are otherwise purged only
     * at season rollover, so a bid left PENDING could still be accepted afterwards and sell a
     * player the club no longer owns. The bidder's interest dies with the contract, so the offers
     * are rejected rather than withdrawn — withdrawal is the bidder's own act.
     */
    public void rejectOpenOffersFor(UUID saveGameId, UUID playerId) {
        List<TransferOffer> open = transferOfferRepository
                .findBySaveGameIdAndPlayerIdAndStatusIn(saveGameId, playerId, OPEN_STATUSES);
        if (open.isEmpty()) {
            return;
        }
        open.forEach(TransferOffer::markRejected);
        transferOfferRepository.saveAll(open);
    }
}
