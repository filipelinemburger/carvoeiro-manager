package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.finance.FinanceTransaction;
import br.com.carvoeiromanager.finance.FinanceTransactionRepository;
import br.com.carvoeiromanager.finance.FinanceTransactionType;
import br.com.carvoeiromanager.match.Lineup;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Performs a transfer atomically (called inside the transfer service's transaction):
 * moves the player to the buyer, debits the buyer / credits the seller, records the
 * managed-club ledger row, and on a managed sale removes the player from the managed lineup
 * (lineup_players row + any set-piece-taker column — no-cascade FK safety).
 */
@Component
public class TransferExecutor {

    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public TransferExecutor(PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            FinanceTransactionRepository financeTransactionRepository,
                            LineupRepository lineupRepository,
                            LineupPlayerRepository lineupPlayerRepository) {
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.financeTransactionRepository = financeTransactionRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    public void execute(SaveGame saveGame, TransferOffer offer, BigDecimal fee) {
        final UUID saveGameId = saveGame.getId();
        final UUID managedClubId = saveGame.getManagedClubId();
        final UUID buyerId = offer.getFromClubId();
        final UUID sellerId = offer.getToClubId();

        Player player = playerRepository.findById(offer.getPlayerId()).orElseThrow();
        Club buyer = clubRepository.findById(buyerId).orElseThrow();
        Club seller = clubRepository.findById(sellerId).orElseThrow();

        // Money + ownership.
        buyer.adjustBalance(fee.negate());
        seller.adjustBalance(fee);
        player.setClubId(buyerId);
        // A completed transfer resolves the listing: the buyer isn't selling their new signing,
        // so the flag/reason must not travel with the player (keeps the market listed-only
        // invariant honest and avoids a stale "Listado" badge on bought players).
        player.unlist();
        // Outgoing buy carries personal terms; a managed sale (incoming) leaves wage null.
        if (offer.getWage() != null) {
            player.setWage(offer.getWage());
            if (offer.getContractYears() != null) {
                player.setContractYears(offer.getContractYears());
            }
        }
        clubRepository.save(buyer);
        clubRepository.save(seller);
        playerRepository.save(player);

        // Managed-club ledger row.
        if (buyerId.equals(managedClubId)) {
            record(saveGame, FinanceTransactionType.TRANSFER_IN, fee.negate(),
                    "Compra: " + player.getName());
        } else if (sellerId.equals(managedClubId)) {
            record(saveGame, FinanceTransactionType.TRANSFER_OUT, fee,
                    "Venda: " + player.getName());
            // The sold player can no longer be in the managed lineup (no-cascade FK safety).
            cleanManagedLineup(saveGameId, managedClubId, player.getId());
        }
    }

    private void cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId) {
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

    private void record(SaveGame saveGame, FinanceTransactionType type, BigDecimal amount, String description) {
        financeTransactionRepository.save(FinanceTransaction.create(
                saveGame.getId(), saveGame.getManagedClubId(), saveGame.getSeasonYear(),
                saveGame.getCurrentRound(), type, amount, description));
    }
}
