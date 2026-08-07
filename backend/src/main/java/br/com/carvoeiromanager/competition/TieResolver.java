package br.com.carvoeiromanager.competition;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides a knockout tie: aggregate over both legs, then the shootout recorded on the second
 * leg. No away-goals rule — it has been abolished in every competition this models.
 */
public final class TieResolver {

    private TieResolver() {
    }

    /**
     * Groups a phase's fixtures into ties by {@code tie_id}, legs ordered 1 then 2, and the
     * resulting list ordered by {@code tie_order} — the seeded bracket position assigned at
     * materialization. {@code tie_id} is an opaque random UUID and would sort arbitrarily;
     * ordering by it here would scramble the bracket (still look plausible, silently wrong) the
     * moment winners are re-paired for the next round by {@code PhaseResolver}.
     */
    public static List<KnockoutTie> tiesOf(List<Fixture> phaseFixtures) {
        Map<UUID, List<Fixture>> byTie = phaseFixtures.stream()
                .filter(f -> f.getTieId() != null)
                .collect(Collectors.groupingBy(Fixture::getTieId));

        return byTie.entrySet().stream()
                .map(entry -> {
                    List<Fixture> legs = entry.getValue().stream()
                            .sorted(Comparator.comparing(Fixture::getLeg)).toList();
                    Fixture first = legs.get(0);
                    Fixture second = legs.size() > 1 ? legs.get(1) : null;
                    // The higher seed hosts the second leg (PhaseResolver's convention).
                    UUID higher = second != null ? second.getHomeClubId() : first.getHomeClubId();
                    UUID lower = second != null ? second.getAwayClubId() : first.getAwayClubId();
                    return new KnockoutTie(entry.getKey(), higher, lower, first, second);
                })
                .sorted(Comparator.comparing(t -> t.firstLeg().getTieOrder()))
                .toList();
    }

    /**
     * The club that advances. Aggregate first; if level, the shootout on the second leg. No
     * away-goals rule.
     *
     * @throws IllegalStateException if either leg is unplayed, or the tie is level with no
     *                               shootout recorded — both indicate a bug upstream, not a
     *                               user-reachable state.
     */
    public static UUID winner(KnockoutTie tie) {
        Fixture first = tie.firstLeg();
        Fixture second = tie.secondLeg();
        if (second == null || !first.isPlayed() || !second.isPlayed()) {
            throw new IllegalStateException("Tie " + tie.tieId() + " is not fully played");
        }
        // The higher seed is away in leg 1 and home in leg 2, so its aggregate is leg 1's away
        // goals plus leg 2's home goals — and vice versa for the lower seed. Inverting this
        // crossover would silently advance the wrong club while still passing structural tests.
        int higherAggregate = first.getAwayGoals() + second.getHomeGoals();
        int lowerAggregate = first.getHomeGoals() + second.getAwayGoals();

        if (higherAggregate != lowerAggregate) {
            return higherAggregate > lowerAggregate ? tie.higherSeedClubId() : tie.lowerSeedClubId();
        }
        if (second.getPenaltyHome() == null || second.getPenaltyAway() == null) {
            throw new IllegalStateException("Level tie " + tie.tieId() + " has no shootout result");
        }
        return second.getPenaltyHome() > second.getPenaltyAway()
                ? tie.higherSeedClubId() : tie.lowerSeedClubId();
    }
}
