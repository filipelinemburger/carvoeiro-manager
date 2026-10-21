package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.player.Player;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * How strong a club is right now: the mean overall of its 16 best players. The board uses it both
 * to set targets and to judge single matches, so the two always agree on who is strong.
 *
 * <p>Deliberately NOT {@code Club.reputation}: that is written once at career creation and never
 * updated, so it is stale by season two. Strength is recomputed every time it is needed.
 */
public final class ClubStrength {

    static final int BEST_N = 16;

    private ClubStrength() { }

    /**
     * {@code squad} must be the players currently playing for the club ({@code club_id}), so a
     * loanee counts for the borrower, not the owner. Youth prospects are filtered out here.
     */
    public static double of(Collection<Player> squad) {
        return ofOveralls(squad.stream().filter(p -> !p.isYouthProspect()).map(Player::getOverall).toList());
    }

    static double ofOveralls(List<Integer> overalls) {
        return overalls.stream()
                .sorted(Comparator.reverseOrder())
                .limit(BEST_N)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);
    }
}
