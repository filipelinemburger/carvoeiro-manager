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

    // Fix index 0, rotate the rest clockwise (standard circle method)
    private void rotate(int[] arr) {
        int last = arr[arr.length - 1];
        for (int i = arr.length - 1; i > 1; i--) arr[i] = arr[i - 1];
        arr[1] = last;
    }
}
