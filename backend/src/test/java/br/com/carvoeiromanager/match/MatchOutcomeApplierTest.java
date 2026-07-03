package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.match.FitnessCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeApplierTest {

    private final MatchOutcomeApplier applier = new MatchOutcomeApplier(new FitnessCalculator());

    private Player player(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void thirdYellowTriggersOneMatchSuspension() {
        Player p = player(Position.MID);
        p.addYellowCard();
        p.addYellowCard(); // now at 2
        List<AttributedEvent> events = List.of(new AttributedEvent(p.getId(), EventType.YELLOW_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 1, 1, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 1, 1, false), acc);

        assertThat(p.getYellowCards()).isEqualTo(3);
        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
        assertThat(acc.newSuspensions()).hasSize(1);
        assertThat(acc.bookings()).extracting(BookingResponse::type).containsExactly("YELLOW_CARD");
    }

    @Test
    void redCardSuspendsImmediately() {
        Player p = player(Position.DEF);
        List<AttributedEvent> events = List.of(new AttributedEvent(p.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(p), 0, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 0, 0, false), acc);

        assertThat(p.getSuspensionRemaining()).isEqualTo(1);
        assertThat(acc.bookings()).extracting(BookingResponse::type).containsExactly("RED_CARD");
    }

    @Test
    void scorerOnWinningTeamGainsForm() {
        Player scorer = player(Position.ATT);
        List<AttributedEvent> events = List.of(new AttributedEvent(scorer.getId(), EventType.GOAL, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(scorer), 3, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 0, 3, false), acc);

        // +1 goal, +1 win = +2
        assertThat(scorer.getForm()).isEqualTo(2);
    }

    @Test
    void defenderLosesFormOnHeavyDefeat() {
        Player def = player(Position.DEF);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(List.of(),
                new MatchOutcomeApplier.TeamOutcome(List.of(def), 0, 4, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 4, 0, false), acc);

        // -1 loss, -1 conceded>=3 = -2
        assertThat(def.getForm()).isEqualTo(-2);
    }

    @Test
    void nonPlayersRegressFormAndServeSuspension() {
        Player hot = player(Position.MID);
        hot.applyForm(3);
        Player banned = player(Position.MID);
        banned.addSuspension(1);

        applier.applyNonPlayers(List.of(hot, banned));

        assertThat(hot.getForm()).isEqualTo(2); // regressed toward 0
        assertThat(banned.getSuspensionRemaining()).isZero(); // served
    }

    @Test
    void exhaustedPlayersAreInjuredWithASeverityTierAndLayoff() {
        Player p = player(Position.MID);
        java.util.Map<java.util.UUID, Integer> fitness = java.util.Map.of(p.getId(), 1); // near-zero fitness
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();
        // Seed 4608: first nextDouble() = 0.0520 < 0.0621 (injury chance at fitness=1, age=25 PRIME),
        // so the injury branch is guaranteed to fire for this near-zero-fitness player.
        applier.applyInjuries(List.of(p), fitness, new java.util.Random(4608), true, acc);

        assertThat(p.getInjuryMatches()).isGreaterThan(0);
        assertThat(p.getInjurySeverity()).isNotEqualTo(br.com.carvoeiromanager.player.InjurySeverity.NONE);
        assertThat(acc.newInjuries()).hasSize(1);
        assertThat(acc.newInjuries().get(0).severity()).isEqualTo(p.getInjurySeverity().name());
    }

    @Test
    void injuryChanceIsHigherForExhaustedThanFreshOverManyRolls() {
        int freshInjuries = countInjuries(100);
        int tiredInjuries = countInjuries(10);
        assertThat(tiredInjuries).isGreaterThan(freshInjuries);
    }

    private int countInjuries(int fitnessValue) {
        int injured = 0;
        java.util.Random rng = new java.util.Random(99);
        for (int i = 0; i < 5000; i++) {
            Player p = player(Position.MID);
            applier.applyInjuries(List.of(p), java.util.Map.of(p.getId(), fitnessValue), rng, false,
                    new MatchOutcomeApplier.Summary());
            if (p.getInjuryMatches() > 0) injured++;
        }
        return injured;
    }

    @Test
    void winnerWhoPlayedGainsMoraleAndLoserLoses() {
        Player winner = player(Position.MID);
        Player loser = player(Position.MID);
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(List.of(),
                new MatchOutcomeApplier.TeamOutcome(List.of(winner), 2, 0, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(loser), 0, 2, false), acc);

        assertThat(winner.getMorale()).isEqualTo(1);   // win +1
        assertThat(loser.getMorale()).isEqualTo(-1);   // loss -1
    }

    @Test
    void scorerGainsExtraMoraleAndRedCardCostsMorale() {
        Player scorer = player(Position.ATT);
        Player sentOff = player(Position.DEF);
        List<AttributedEvent> events = List.of(
                new AttributedEvent(scorer.getId(), EventType.GOAL, true),
                new AttributedEvent(sentOff.getId(), EventType.RED_CARD, true));
        MatchOutcomeApplier.Summary acc = new MatchOutcomeApplier.Summary();

        applier.applyFixture(events,
                new MatchOutcomeApplier.TeamOutcome(List.of(scorer, sentOff), 1, 1, true),
                new MatchOutcomeApplier.TeamOutcome(List.of(), 1, 1, false), acc);

        assertThat(scorer.getMorale()).isEqualTo(1);    // draw 0 + scored +1
        assertThat(sentOff.getMorale()).isEqualTo(-1);  // draw 0 + red -1
    }

    @Test
    void unusedReservesDriftTowardMildDiscontent() {
        Player fresh = player(Position.MID);        // morale 0 -> -1
        Player low = player(Position.MID);
        low.applyMorale(-3);                         // below -1 -> steps back up toward -1

        applier.applyNonPlayers(List.of(fresh, low));

        assertThat(fresh.getMorale()).isEqualTo(-1);
        assertThat(low.getMorale()).isEqualTo(-2);
    }
}
