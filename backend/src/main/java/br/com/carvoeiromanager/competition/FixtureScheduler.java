package br.com.carvoeiromanager.competition;

import java.util.ArrayList;
import java.util.List;

public class FixtureScheduler {

    /**
     * How many rounds a single leg of a round-robin needs for {@code teamCount} clubs. An even
     * field pairs everyone off every round and is done in {@code teamCount - 1}; an odd field
     * cannot, so one club sits out each round and it takes {@code teamCount} rounds instead.
     * {@code SeedPhaseValidator} uses this to check a seeded phase's declared round count against
     * what the scheduler will actually produce.
     *
     * <p>Guarded at 2 like {@link #doubleRoundRobin(int)} and {@link #singleRoundRobin(int)}: a
     * field of one cannot play itself. Unguarded, {@code roundsPerLeg(0)} returned {@code -1} and
     * that nonsense travelled — {@code SeedPhaseValidator} reported "0 members over 1 leg(s)
     * produce -1" instead of naming the real problem, and {@code SaveGameService.totalRoundsFor}
     * would have persisted a {@code Competition} with {@code totalRounds = -2}. Both failed
     * eventually anyway; failing here makes the message match the fault.
     */
    public static int roundsPerLeg(int teamCount) {
        if (teamCount < 2) {
            throw new IllegalArgumentException("teamCount must be >= 2");
        }
        return teamCount % 2 == 0 ? teamCount - 1 : teamCount;
    }

    /**
     * Circle-method slots: an odd field borrows one phantom slot so the rotation still pairs the
     * whole circle. Whoever draws the phantom (any index {@code >= teamCount}) has a bye.
     */
    private static int slots(int teamCount) {
        return teamCount % 2 == 0 ? teamCount : teamCount + 1;
    }

    public List<ScheduledFixture> doubleRoundRobin(int teamCount) {
        if (teamCount < 2) {
            throw new IllegalArgumentException("teamCount must be >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = slots(teamCount);
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        int roundsPerLeg = n - 1;
        for (int round = 0; round < roundsPerLeg; round++) {
            for (int i = 0; i < n / 2; i++) {
                int home = arr[i];
                int away = arr[n - 1 - i];
                if (home >= teamCount || away >= teamCount) continue; // phantom slot: a bye
                // alternate home/away by round for fairness
                if (round % 2 == 1) { int tmp = home; home = away; away = tmp; }
                result.add(new ScheduledFixture(round, home, away));               // first leg
                result.add(new ScheduledFixture(round + roundsPerLeg, away, home)); // second leg (reversed)
            }
            rotate(arr);
        }
        return result;
    }

    /**
     * One leg only: every pair meets exactly once over {@link #roundsPerLeg(int)} rounds. Uses the
     * same circle method as {@link #doubleRoundRobin(int)} to decide who plays whom and when,
     * but a single leg over an even field gives each club an odd number of matches
     * ({@code teamCount - 1}), so a fixed round-parity swap (as used by
     * {@link #doubleRoundRobin(int)}, where every club plays an even number of matches) is not
     * enough to keep every club balanced — the fixed anchor club at position 0 skews the
     * rotation. Instead, each match's home side is whichever of the two clubs currently has fewer
     * home games minus away games so far; ties (both clubs equally balanced) are broken by
     * alternating a running match counter, so no club is systematically favored. This keeps every
     * club's home/away split within one of each other.
     *
     * <p>An odd field cannot pair everyone off every round, so one phantom slot joins the circle
     * and whoever draws it takes a bye: {@code teamCount} rounds of {@code (teamCount - 1) / 2}
     * matches each. Série C spends the seed's intake at 13 members before it reaches 20.
     */
    public List<ScheduledFixture> singleRoundRobin(int teamCount) {
        if (teamCount < 2) {
            throw new IllegalArgumentException("teamCount must be >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = slots(teamCount);
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        int[] homeCount = new int[n];
        int[] awayCount = new int[n];
        int matchIndex = 0;
        boolean evenField = teamCount % 2 == 0;

        for (int round = 0; round < n - 1; round++) {
            for (int i = 0; i < n / 2; i++) {
                int clubA = arr[i];
                int clubB = arr[n - 1 - i];
                if (clubA >= teamCount || clubB >= teamCount) continue; // phantom slot: a bye
                int balanceA = homeCount[clubA] - awayCount[clubA];
                int balanceB = homeCount[clubB] - awayCount[clubB];
                int home;
                int away;
                if (balanceA < balanceB) {
                    home = clubA; away = clubB;
                } else if (balanceB < balanceA) {
                    home = clubB; away = clubA;
                } else if (evenField ? matchIndex % 2 == 0 : (round + i) % 2 == 0) {
                    // Tie-break when both clubs are equally balanced. An even field alternates on
                    // a running match counter; a bye makes that counter drift out of step with
                    // the circle, so an odd field alternates on the slot's position in the round
                    // instead, which lands every club on an exact home/away split.
                    home = clubA; away = clubB;
                } else {
                    home = clubB; away = clubA;
                }
                matchIndex++;
                homeCount[home]++;
                awayCount[away]++;
                result.add(new ScheduledFixture(round, home, away));
            }
            rotate(arr);
        }
        return result;
    }

    /**
     * Seeded knockout pairings: best against worst, second against second-worst, and so on.
     * Indices are positions in the seeded ordering (0 = top seed). Each entry is
     * {@code {higherSeedIndex, lowerSeedIndex}}; the higher seed hosts the second leg.
     */
    public List<int[]> knockoutPairings(int qualifierCount) {
        if (qualifierCount < 2 || Integer.bitCount(qualifierCount) != 1) {
            throw new IllegalArgumentException("qualifierCount must be a power of two and >= 2");
        }
        List<int[]> ties = new ArrayList<>();
        for (int i = 0; i < qualifierCount / 2; i++) {
            ties.add(new int[] { i, qualifierCount - 1 - i });
        }
        return ties;
    }

    // Fix index 0, rotate the rest clockwise (standard circle method)
    private void rotate(int[] arr) {
        int last = arr[arr.length - 1];
        for (int i = arr.length - 1; i > 1; i--) arr[i] = arr[i - 1];
        arr[1] = last;
    }
}
