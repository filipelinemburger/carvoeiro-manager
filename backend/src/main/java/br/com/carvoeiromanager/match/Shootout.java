package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Penalty shootout: best of five, then sudden death. Pure and seeded — no Spring, no I/O.
 * Takers are the players on the pitch at the end of 120 minutes, best finishers first, then
 * cycling in the same order once the list is exhausted.
 *
 * <p>Shootout conversions are NOT goals: they decide who advances and never enter standings,
 * top scorers, or player statistics.
 */
public final class Shootout {

    /** Conversion odds for an average finisher against an average keeper. */
    private static final double BASE_CONVERSION = 0.76;
    private static final double FINISHING_WEIGHT = 0.0035;
    private static final double KEEPER_WEIGHT = 0.0030;
    private static final double MIN_CONVERSION = 0.35;
    private static final double MAX_CONVERSION = 0.95;

    /** Kicks each side takes before sudden death. */
    private static final int BEST_OF = 5;

    /** Guards against a pathological infinite sudden death. */
    private static final int MAX_ROUNDS = 25;

    private Shootout() {
    }

    public record Kick(boolean home, UUID takerId, int round, boolean scored) { }

    public record Result(int homeScore, int awayScore, List<Kick> kicks) { }

    public static Result decide(List<Player> homeTakers, List<Player> awayTakers,
                                Player homeKeeper, Player awayKeeper, long seed) {
        Random rng = new Random(seed ^ 0x5EED9E4A17L);
        List<Player> home = orderedTakers(homeTakers);
        List<Player> away = orderedTakers(awayTakers);

        List<Kick> kicks = new ArrayList<>();
        int homeScore = 0;
        int awayScore = 0;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            boolean suddenDeath = round > BEST_OF;

            if (!suddenDeath && decided(homeScore, awayScore, round, true)) break;
            Player homeTaker = home.get((round - 1) % home.size());
            boolean homeScored = convert(homeTaker, awayKeeper, rng);
            kicks.add(new Kick(true, homeTaker.getId(), round, homeScored));
            if (homeScored) homeScore++;

            if (!suddenDeath && decided(homeScore, awayScore, round, false)) break;
            Player awayTaker = away.get((round - 1) % away.size());
            boolean awayScored = convert(awayTaker, homeKeeper, rng);
            kicks.add(new Kick(false, awayTaker.getId(), round, awayScored));
            if (awayScored) awayScore++;

            // A completed round with a lead ends it. This covers both the end of the best-of-five
            // (five kicks each, someone ahead) and every sudden-death round. decided() only stops
            // things EARLY, when the trailing side can no longer catch up — it says nothing about
            // regulation simply being over, so without this a 4-3 best-of-five would spill into
            // sudden death and could hand the tie to the side that had already lost it.
            if (round >= BEST_OF && homeScore != awayScore) break;
        }

        // MAX_ROUNDS reached while still level: the better cumulative finisher advances.
        if (homeScore == awayScore) {
            homeScore += averageFinishing(home) >= averageFinishing(away) ? 1 : 0;
            awayScore += averageFinishing(home) >= averageFinishing(away) ? 0 : 1;
        }
        return new Result(homeScore, awayScore, List.copyOf(kicks));
    }

    /** Best finishers first; ties broken by id so the order is stable across runs. */
    private static List<Player> orderedTakers(List<Player> takers) {
        return takers.stream()
                .sorted(Comparator.comparingInt(Player::getFinishing).reversed()
                        .thenComparing(Player::getId))
                .toList();
    }

    private static boolean convert(Player taker, Player keeper, Random rng) {
        double p = BASE_CONVERSION
                + FINISHING_WEIGHT * (taker.getFinishing() - 50)
                - KEEPER_WEIGHT * (keeper.getGoalkeeping() - 50);
        return rng.nextDouble() < Math.max(MIN_CONVERSION, Math.min(MAX_CONVERSION, p));
    }

    /**
     * True when the remaining kicks of the best-of-five cannot change the outcome, i.e. one side
     * already leads by more than the other can still reach.
     *
     * <p>Home kicks first in each round, so at round {@code r} away has always taken {@code r - 1}
     * kicks, while home has taken {@code r - 1} before its own kick and {@code r} after it.
     * Getting this backwards ends the shootout a kick early and can hand the lead an outright win
     * in a position the trailing side could still have levelled.
     *
     * <p>Package-private so {@link ShootoutTest} can pin the boundary directly rather than trying
     * to steer the RNG into it.
     */
    static boolean decided(int homeScore, int awayScore, int round, boolean beforeHomeKick) {
        int homeTaken = beforeHomeKick ? round - 1 : round;
        int awayTaken = round - 1;
        int homeRemaining = Math.max(0, BEST_OF - homeTaken);
        int awayRemaining = Math.max(0, BEST_OF - awayTaken);
        return homeScore > awayScore + awayRemaining || awayScore > homeScore + homeRemaining;
    }

    private static double averageFinishing(List<Player> takers) {
        return takers.stream().mapToInt(Player::getFinishing).average().orElse(0);
    }
}
