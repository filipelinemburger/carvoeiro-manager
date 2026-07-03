package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a finished match's timeline data into a 0–10 performance grade per player, à la Football
 * Manager. Pure and deterministic — no RNG, identical inputs give identical grades — so the same
 * grades can be computed independently for the live full-time screen and the persisted news summary.
 *
 * <p>Grade model (base 6.0):
 * <ul>
 *   <li>+1.0 per goal scored;</li>
 *   <li>team result: +0.6 win / 0 draw / −0.4 loss;</li>
 *   <li>defensive contribution by position — a clean sheet rewards GK +1.0 / DEF +0.8 / MID +0.3,
 *       otherwise goals conceded cost the GK 0.30 each and a DEF 0.15 each;</li>
 *   <li>discipline: −0.4 per yellow, −1.5 for a red card;</li>
 *   <li>cameo muting: for appearances under 30 minutes every non-goal contribution is scaled toward
 *       the 6.0 baseline (a 15-minute sub gets half the drift), but a goal always counts in full.</li>
 * </ul>
 * The result is clamped to [3.0, 10.0] and rounded to one decimal.
 */
@Service
public class PlayerRatingCalculator {

    private static final double BASE = 6.0;
    private static final double GOAL_BONUS = 1.0;
    private static final double WIN_BONUS = 0.6;
    private static final double LOSS_PENALTY = -0.4;
    private static final double GK_CLEAN_SHEET = 1.0;
    private static final double DEF_CLEAN_SHEET = 0.8;
    private static final double MID_CLEAN_SHEET = 0.3;
    private static final double GK_CONCEDED = -0.30;
    private static final double DEF_CONCEDED = -0.15;
    private static final double YELLOW_PENALTY = -0.4;
    private static final double RED_PENALTY = -1.5;
    private static final int CAMEO_MINUTES = 30;
    private static final double FLOOR = 3.0;
    private static final double CEILING = 10.0;

    /**
     * Grades one side's players. Only players with recorded minutes {@code > 0} are returned (bench
     * players who never came on are skipped).
     *
     * @param played        the side's players who could have featured (starters + used subs)
     * @param sideEvents    attributed goal/card events for this side (others are ignored via id lookup)
     * @param minutesPlayed minutes each player was on the pitch, by player id
     * @param scored        goals this side scored
     * @param conceded      goals this side conceded
     */
    public List<PlayerRating> rate(List<Player> played, List<AttributedEvent> sideEvents,
                                   Map<UUID, Integer> minutesPlayed, int scored, int conceded) {
        Map<UUID, Integer> goals = new HashMap<>();
        Map<UUID, Integer> yellows = new HashMap<>();
        Set<UUID> reds = new HashSet<>();
        for (AttributedEvent e : sideEvents) {
            switch (e.type()) {
                case GOAL, SET_PIECE_GOAL -> goals.merge(e.playerId(), 1, Integer::sum);
                case YELLOW_CARD -> yellows.merge(e.playerId(), 1, Integer::sum);
                case RED_CARD -> reds.add(e.playerId());
                default -> { }
            }
        }

        double result = scored > conceded ? WIN_BONUS : (scored < conceded ? LOSS_PENALTY : 0.0);

        List<PlayerRating> ratings = new ArrayList<>();
        for (Player p : played) {
            int minutes = minutesPlayed.getOrDefault(p.getId(), 0);
            if (minutes <= 0) {
                continue;   // never came on
            }
            int scoredGoals = goals.getOrDefault(p.getId(), 0);

            double nonGoal = result
                    + defensiveDelta(p.getPosition(), conceded)
                    + YELLOW_PENALTY * yellows.getOrDefault(p.getId(), 0)
                    + (reds.contains(p.getId()) ? RED_PENALTY : 0.0);

            // Short cameos are judged on a smaller sample: mute every non-goal contribution toward
            // the baseline, but let a goal count in full.
            if (minutes < CAMEO_MINUTES) {
                nonGoal *= (double) minutes / CAMEO_MINUTES;
            }

            double raw = BASE + GOAL_BONUS * scoredGoals + nonGoal;
            double rating = round1(clamp(raw));
            ratings.add(new PlayerRating(p.getId().toString(), p.getName(),
                    p.getPosition().name(), minutes, scoredGoals, rating));
        }
        return ratings;
    }

    private double defensiveDelta(Position position, int conceded) {
        boolean cleanSheet = conceded == 0;
        return switch (position) {
            case GK -> cleanSheet ? GK_CLEAN_SHEET : GK_CONCEDED * conceded;
            case DEF -> cleanSheet ? DEF_CLEAN_SHEET : DEF_CONCEDED * conceded;
            case MID -> cleanSheet ? MID_CLEAN_SHEET : 0.0;
            case ATT -> 0.0;
        };
    }

    private double clamp(double v) {
        return Math.max(FLOOR, Math.min(CEILING, v));
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
