package br.com.carvoeiromanager.competition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a competition's phase results into one ordering — the Brazilian
 * <em>classificação final</em>. Champion, runner-up, then each knockout round's losers as a
 * band ordered by first-phase position, then everyone who never qualified in table order.
 *
 * <p>A competition with no knockout phases returns its table order unchanged, so plain
 * leagues behave exactly as before.
 *
 * <p>Callers must only pass knockout phases that are fully resolved (every tie played to a
 * winner) — this is a pure function over already-final results, not a "has this finished yet?"
 * check. In practice that means calling it once the season is confirmed finished (see
 * {@code SeasonAdvanceService}'s {@code noMoreMatchEvents}), never mid-bracket.
 */
public final class FinalClassification {

    private FinalClassification() {
    }

    public static List<UUID> compute(List<StandingRow> firstPhaseTable,
                                     List<CompetitionPhase> knockoutPhases,
                                     Map<UUID, List<Fixture>> fixturesByPhase) {
        List<UUID> tableOrder = firstPhaseTable.stream().map(StandingRow::clubId).toList();
        if (knockoutPhases.isEmpty()) {
            return tableOrder;
        }
        // First-phase position, used to order every band internally.
        Comparator<UUID> byTablePosition = Comparator.comparingInt(tableOrder::indexOf);

        List<CompetitionPhase> ordered = knockoutPhases.stream()
                .sorted(Comparator.comparingInt(CompetitionPhase::getOrdinal)).toList();

        Set<UUID> placed = new LinkedHashSet<>();
        List<UUID> result = new ArrayList<>();

        // The last knockout phase is the final: its winner is 1st, its loser 2nd.
        CompetitionPhase last = ordered.get(ordered.size() - 1);
        List<KnockoutTie> finalTies = TieResolver.tiesOf(
                fixturesByPhase.getOrDefault(last.getId(), List.of()));
        KnockoutTie decider = finalTies.get(0);
        UUID champion = TieResolver.winner(decider);
        UUID runnerUp = champion.equals(decider.higherSeedClubId())
                ? decider.lowerSeedClubId() : decider.higherSeedClubId();
        result.add(champion);
        result.add(runnerUp);
        placed.add(champion);
        placed.add(runnerUp);

        // Earlier knockout rounds, latest first: each round's losers form the next band.
        for (int i = ordered.size() - 2; i >= 0; i--) {
            List<UUID> losers = new ArrayList<>();
            List<Fixture> phaseFixtures = fixturesByPhase.getOrDefault(ordered.get(i).getId(), List.of());
            for (KnockoutTie tie : TieResolver.tiesOf(phaseFixtures)) {
                UUID winner = TieResolver.winner(tie);
                UUID loser = winner.equals(tie.higherSeedClubId())
                        ? tie.lowerSeedClubId() : tie.higherSeedClubId();
                if (placed.add(loser)) {
                    losers.add(loser);
                }
            }
            losers.sort(byTablePosition);
            result.addAll(losers);
        }

        // Everyone who never reached the knockout, in first-phase order.
        tableOrder.stream().filter(id -> !placed.contains(id)).forEach(result::add);
        return List.copyOf(result);
    }
}
