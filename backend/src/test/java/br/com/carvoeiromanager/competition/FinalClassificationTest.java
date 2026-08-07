package br.com.carvoeiromanager.competition;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit-level coverage for {@link FinalClassification}. No Spring, no database — phases and
 * fixtures are built directly, the same way {@link TieResolverTest} builds ties, so the bracket
 * outcome (who wins/loses each tie) is fully controlled rather than derived from any seeding
 * convention. Which club is passed as "lower" vs "higher" seed here is arbitrary plumbing for
 * {@link TieResolver}; {@link FinalClassification} only cares about winner vs loser.
 */
class FinalClassificationTest {

    private static final UUID SAVE_GAME_ID = UUID.randomUUID();
    private static final UUID COMPETITION_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2025, 6, 1);

    // 12-club first-phase table, index 0 = 1st place ... index 11 = 12th place, mirroring the
    // Catarinense: club0..club7 qualify for the knockout, club8..club11 never do.
    private final List<UUID> clubs = new ArrayList<>();

    {
        for (int i = 0; i < 12; i++) {
            clubs.add(UUID.randomUUID());
        }
    }

    private UUID club(int position) {
        return clubs.get(position);
    }

    private StandingRow row(int position) {
        UUID id = club(position);
        String name = "Club " + position;
        // Points only need to be monotonically consistent with table order; nothing here reads
        // them back — FinalClassification orders purely from the list's index.
        return new StandingRow(id, name, name, 11, 11 - position, 0, position,
                20 - position, position, 20 - 2 * position, (11 - position) * 3);
    }

    private List<StandingRow> firstPhaseTable() {
        List<StandingRow> table = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            table.add(row(i));
        }
        return table;
    }

    /**
     * Builds one two-legged tie's fixtures. {@code a} hosts leg 1, {@code b} hosts leg 2 — pure
     * plumbing so {@link TieResolver} can assign higher/lower seed; the actual winner is forced
     * via the scripted scoreline regardless of which side is "higher".
     */
    private List<Fixture> twoLegTie(UUID a, UUID b, UUID winner, int tieOrder) {
        UUID tieId = UUID.randomUUID();
        Fixture leg1 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 1, a, b, DATE);
        Fixture leg2 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 2, b, a, DATE);
        if (winner.equals(b)) {
            leg1.recordResult(0, 1); // a (home) 0 - b (away) 1
            leg2.recordResult(1, 0); // b (home) 1 - a (away) 0 -> aggregate b 2-0
        } else {
            leg1.recordResult(1, 0); // a (home) 1 - b (away) 0
            leg2.recordResult(0, 1); // b (home) 0 - a (away) 1 -> aggregate a 2-0
        }
        leg1.setLeg(1);
        leg1.setTieId(tieId);
        leg1.setTieOrder(tieOrder);
        leg2.setLeg(2);
        leg2.setTieId(tieId);
        leg2.setTieOrder(tieOrder);
        return List.of(leg1, leg2);
    }

    private List<Fixture> concat(List<Fixture>... ties) {
        List<Fixture> all = new ArrayList<>();
        for (List<Fixture> tie : ties) {
            all.addAll(tie);
        }
        return all;
    }

    /** One shared bracket used by both knockout-path tests, described in the class javadoc math. */
    private record Bracket(List<CompetitionPhase> knockoutPhases, Map<UUID, List<Fixture>> fixturesByPhase) {
    }

    private Bracket buildBracket() {
        CompetitionPhase quarterFinal = CompetitionPhase.create(SAVE_GAME_ID, COMPETITION_ID, 1,
                "Quartas de Final", PhaseKind.KNOCKOUT, 2, 2, null);
        CompetitionPhase semiFinal = CompetitionPhase.create(SAVE_GAME_ID, COMPETITION_ID, 2,
                "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null);
        CompetitionPhase finalPhase = CompetitionPhase.create(SAVE_GAME_ID, COMPETITION_ID, 3,
                "Final", PhaseKind.KNOCKOUT, 2, 2, null);

        // Quarterfinals: club0, club1, club5 (upset over club2), club3 advance.
        List<Fixture> qf = concat(
                twoLegTie(club(7), club(0), club(0), 0),
                twoLegTie(club(6), club(1), club(1), 1),
                twoLegTie(club(5), club(2), club(5), 2),
                twoLegTie(club(4), club(3), club(3), 3));

        // Semifinals: club0 and club1 advance to the final; club5 (6th) and club3 (4th) lose.
        List<Fixture> sf = concat(
                twoLegTie(club(5), club(0), club(0), 0),
                twoLegTie(club(3), club(1), club(1), 1));

        // Final: club0 champion, club1 runner-up.
        List<Fixture> theFinal = twoLegTie(club(1), club(0), club(0), 0);

        List<CompetitionPhase> knockoutPhases = List.of(quarterFinal, semiFinal, finalPhase);
        Map<UUID, List<Fixture>> fixturesByPhase = Map.of(
                quarterFinal.getId(), qf,
                semiFinal.getId(), sf,
                finalPhase.getId(), theFinal);
        return new Bracket(knockoutPhases, fixturesByPhase);
    }

    @Test
    void championThenRunnerUpThenSemifinalistsThenQuarterfinalists() {
        List<StandingRow> firstPhaseTable = firstPhaseTable();
        Bracket bracket = buildBracket();

        UUID champion = club(0);
        UUID runnerUp = club(1);
        List<UUID> losingSemifinalists = List.of(club(3), club(5));
        List<UUID> losingQuarterfinalists = List.of(club(2), club(4), club(6), club(7));
        List<UUID> nonQualifiersInTableOrder = List.of(club(8), club(9), club(10), club(11));

        List<UUID> order = FinalClassification.compute(
                firstPhaseTable, bracket.knockoutPhases(), bracket.fixturesByPhase());

        assertThat(order).hasSize(12);
        assertThat(order.get(0)).isEqualTo(champion);
        assertThat(order.get(1)).isEqualTo(runnerUp);
        assertThat(order.subList(2, 4)).containsExactlyInAnyOrderElementsOf(losingSemifinalists);
        assertThat(order.subList(4, 8)).containsExactlyInAnyOrderElementsOf(losingQuarterfinalists);
        assertThat(order.subList(8, 12)).containsExactlyElementsOf(nonQualifiersInTableOrder);
    }

    @Test
    void tiedGroupsBreakOnFirstPhasePosition() {
        List<StandingRow> firstPhaseTable = firstPhaseTable();
        Bracket bracket = buildBracket();

        // Both lost in the semifinal (same knockout round), so within that band the tiebreak is
        // strictly first-phase position: club3 finished 4th, club5 finished 6th.
        UUID semifinalistWhoFinishedFourth = club(3);
        UUID semifinalistWhoFinishedSixth = club(5);

        List<UUID> order = FinalClassification.compute(
                firstPhaseTable, bracket.knockoutPhases(), bracket.fixturesByPhase());

        int betterSemifinalist = order.indexOf(semifinalistWhoFinishedFourth);
        int worseSemifinalist = order.indexOf(semifinalistWhoFinishedSixth);
        assertThat(betterSemifinalist).isLessThan(worseSemifinalist);
    }

    @Test
    void aCompetitionWithNoKnockoutPhasesReturnsTheTableOrder() {
        List<StandingRow> serieBTable = List.of(
                new StandingRow(UUID.randomUUID(), "A", "A", 10, 8, 1, 1, 20, 5, 15, 25),
                new StandingRow(UUID.randomUUID(), "B", "B", 10, 5, 2, 3, 15, 10, 5, 17),
                new StandingRow(UUID.randomUUID(), "C", "C", 10, 2, 3, 5, 8, 15, -7, 9));

        List<UUID> order = FinalClassification.compute(serieBTable, List.of(), Map.of());

        assertThat(order).containsExactlyElementsOf(
                serieBTable.stream().map(StandingRow::clubId).toList());
    }
}
