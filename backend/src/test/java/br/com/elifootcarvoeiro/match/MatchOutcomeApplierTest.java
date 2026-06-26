package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchOutcomeApplierTest {

    private final MatchOutcomeApplier applier = new MatchOutcomeApplier();

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
}
