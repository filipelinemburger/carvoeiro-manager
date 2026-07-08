package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@link SellerContext} the AI uses to price a buy offer. A player is a
 * {@code keyPlayer} when they rank in the top 11 by overall in their own (non-youth) squad;
 * {@code noCover} when no other player in the same position is within 3 overall of them;
 * financial health comes straight from {@link ClubFinancialHealthEvaluator}.
 */
@Component
public class SellerContextFactory {

    static final int KEY_PLAYER_RANK_THRESHOLD = 11;
    static final int COVER_OVERALL_MARGIN = 3;

    private final PlayerRepository playerRepository;
    private final ClubFinancialHealthEvaluator financialHealthEvaluator;

    public SellerContextFactory(PlayerRepository playerRepository,
                                 ClubFinancialHealthEvaluator financialHealthEvaluator) {
        this.playerRepository = playerRepository;
        this.financialHealthEvaluator = financialHealthEvaluator;
    }

    public SellerContext build(Player player, Club sellerClub) {
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(sellerClub.getId());
        return new SellerContext(player.isTransferListed(), isKeyPlayer(player, squad),
                isNoCover(player, squad), financialHealthEvaluator.evaluate(sellerClub));
    }

    /** Rank 1 = highest overall (ties broken by id, ascending); key player iff rank <= 11. */
    private boolean isKeyPlayer(Player player, List<Player> squad) {
        List<Player> ranked = squad.stream()
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getId))
                .toList();
        int rank = ranked.size() + 1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getId().equals(player.getId())) {
                rank = i + 1;
                break;
            }
        }
        return rank <= KEY_PLAYER_RANK_THRESHOLD;
    }

    /** No cover iff no OTHER same-position squadmate has overall >= player.overall - 3. */
    private boolean isNoCover(Player player, List<Player> squad) {
        int coverThreshold = player.getOverall() - COVER_OVERALL_MARGIN;
        return squad.stream()
                .filter(p -> !p.getId().equals(player.getId()))
                .filter(p -> p.getPosition() == player.getPosition())
                .noneMatch(p -> p.getOverall() >= coverThreshold);
    }
}
