package br.com.carvoeiromanager.board;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * What the board asks for, from where it expects the club to finish (spec §5.2–5.3). The board asks
 * for a little <em>less</em> than the club's rank, because the table is noisy.
 *
 * <p>Bands are written for the real field sizes (20 per national division, 12 for the state
 * championship) and scaled proportionally — {@code ceil(bound · n / base)} — for any other size.
 */
public final class ObjectiveTargets {

    private static final int NATIONAL_BASE = 20;
    private static final int STATE_BASE = 12;

    // Upper expected-rank bound of each band → the target. Série A: G-4, G-6, top 12, out of the Z-4.
    private static final int[] A_BOUNDS = {3, 8, 14, 20};
    private static final int[] A_TARGETS = {4, 6, 12, 16};
    // Série B: G-4, top 8, first half, out of the Z-4.
    private static final int[] B_BOUNDS = {3, 7, 13, 20};
    private static final int[] B_TARGETS = {4, 8, 10, 16};
    // Série C is phased (first phase, then quarterfinals onwards): a stage for the strong, and a
    // first-phase position for the weakest band, who are not expected to qualify at all.
    private static final int[] C_BOUNDS = {2, 5, 10, 20};
    private static final Stage[] C_STAGES = {Stage.FINAL, Stage.SEMIFINAL, Stage.QUARTERFINAL, null};
    private static final int C_WEAKEST_POSITION = 14;
    // The state championship (12 clubs).
    private static final int[] STATE_BOUNDS = {2, 4, 6, 12};
    private static final Stage[] STATE_STAGES = {Stage.CHAMPION, Stage.FINAL, Stage.SEMIFINAL, Stage.QUARTERFINAL};

    private ObjectiveTargets() { }

    /** 1-based rank of {@code clubId} by strength, strongest first; ties broken by club name. */
    public static int expectedRank(UUID clubId, Map<UUID, Double> strengthByClub, Map<UUID, String> nameByClub) {
        var ordered = strengthByClub.keySet().stream()
                .sorted(Comparator.<UUID>comparingDouble(strengthByClub::get).reversed()
                        .thenComparing(id -> nameByClub.getOrDefault(id, "")))
                .toList();
        int index = ordered.indexOf(clubId);
        if (index < 0) {
            throw new IllegalArgumentException("club " + clubId + " is not among the ranked clubs");
        }
        return index + 1;
    }

    public static LeagueTarget league(int tier, int expectedRank, int fieldSize) {
        return switch (tier) {
            case 1 -> LeagueTarget.position(scale(A_TARGETS[band(A_BOUNDS, expectedRank, fieldSize, NATIONAL_BASE)],
                    fieldSize, NATIONAL_BASE));
            case 2 -> LeagueTarget.position(scale(B_TARGETS[band(B_BOUNDS, expectedRank, fieldSize, NATIONAL_BASE)],
                    fieldSize, NATIONAL_BASE));
            case 3 -> {
                Stage stage = C_STAGES[band(C_BOUNDS, expectedRank, fieldSize, NATIONAL_BASE)];
                yield stage != null ? LeagueTarget.stage(stage)
                        : LeagueTarget.position(scale(C_WEAKEST_POSITION, fieldSize, NATIONAL_BASE));
            }
            default -> throw new IllegalArgumentException("no board targets for division tier " + tier);
        };
    }

    public static Stage state(int expectedRank, int fieldSize) {
        return STATE_STAGES[band(STATE_BOUNDS, expectedRank, fieldSize, STATE_BASE)];
    }

    private static int band(int[] bounds, int rank, int fieldSize, int base) {
        for (int i = 0; i < bounds.length; i++) {
            if (rank <= scale(bounds[i], fieldSize, base)) {
                return i;
            }
        }
        return bounds.length - 1;
    }

    private static int scale(int value, int fieldSize, int base) {
        return (int) Math.ceil(value * (double) fieldSize / base);
    }
}
