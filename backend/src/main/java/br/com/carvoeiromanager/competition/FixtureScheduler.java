package br.com.carvoeiromanager.competition;

import java.util.ArrayList;
import java.util.List;

public class FixtureScheduler {

    public List<ScheduledFixture> doubleRoundRobin(int teamCount) {
        if (teamCount < 2 || teamCount % 2 != 0) {
            throw new IllegalArgumentException("teamCount must be even and >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = teamCount;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        int roundsPerLeg = n - 1;
        for (int round = 0; round < roundsPerLeg; round++) {
            for (int i = 0; i < n / 2; i++) {
                int home = arr[i];
                int away = arr[n - 1 - i];
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
     * One leg only: every pair meets exactly once over {@code teamCount - 1} rounds. Uses the
     * same circle method as {@link #doubleRoundRobin(int)} to decide who plays whom and when,
     * but a single leg gives each club an odd number of matches ({@code teamCount - 1}), so a
     * fixed round-parity swap (as used by {@link #doubleRoundRobin(int)}, where every club plays
     * an even number of matches) is not enough to keep every club balanced — the fixed anchor
     * club at position 0 skews the rotation. Instead, each match's home side is whichever of the
     * two clubs currently has fewer home games minus away games so far; ties (both clubs equally
     * balanced) are broken by alternating a running match counter, so no club is systematically
     * favored. This keeps every club's home/away split within one of each other.
     */
    public List<ScheduledFixture> singleRoundRobin(int teamCount) {
        if (teamCount < 2 || teamCount % 2 != 0) {
            throw new IllegalArgumentException("teamCount must be even and >= 2");
        }
        List<ScheduledFixture> result = new ArrayList<>();
        int n = teamCount;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;

        int[] homeCount = new int[n];
        int[] awayCount = new int[n];
        int matchIndex = 0;

        for (int round = 0; round < n - 1; round++) {
            for (int i = 0; i < n / 2; i++) {
                int clubA = arr[i];
                int clubB = arr[n - 1 - i];
                int balanceA = homeCount[clubA] - awayCount[clubA];
                int balanceB = homeCount[clubB] - awayCount[clubB];
                int home;
                int away;
                if (balanceA < balanceB) {
                    home = clubA; away = clubB;
                } else if (balanceB < balanceA) {
                    home = clubB; away = clubA;
                } else if (matchIndex % 2 == 0) {
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
