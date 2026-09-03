package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.player.Player;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Whether a player may be fielded against a particular opponent. Unlike {@code Player.isEligible},
 * which asks only about the player, this depends on who he is playing: a borrowed player is barred
 * from facing the club that owns him unless the loan negotiated the release.
 */
@Component
public class LoanEligibility {

    /** @param opponentClubId the club he would face, or null when no fixture is in view */
    public boolean mayFace(Player player, UUID opponentClubId) {
        if (opponentClubId == null || !player.isOnLoan()) {
            return true;
        }
        if (Boolean.TRUE.equals(player.getLoanMayFaceOwner())) {
            return true;
        }
        return !opponentClubId.equals(player.getOwnerClubId());
    }
}
