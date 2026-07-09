package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerRatingCalculatorTest {

    private final PlayerRatingCalculator calculator = new PlayerRatingCalculator();

    private Player player(Position pos) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    private PlayerRating rateOne(Player p, List<AttributedEvent> events, int minutes, int scored, int conceded) {
        List<PlayerRating> out = calculator.rate(List.of(p), events, Map.of(p.getId(), minutes), scored, conceded);
        return out.get(0);
    }

    @Test
    void attackerWhoScoresInAWinIsGradedHighly() {
        // Given a striker who scored once and played the full match in a 2-0 win
        Player att = player(Position.ATT);
        List<AttributedEvent> events = List.of(new AttributedEvent(att.getId(), EventType.GOAL, true));

        // When rated
        PlayerRating r = rateOne(att, events, 90, 2, 0);

        // Then base 6.0 + 1.0 goal + 0.6 win = 7.6 (attackers get no clean-sheet bonus)
        assertThat(r.rating()).isEqualTo(7.6);
        assertThat(r.goals()).isEqualTo(1);
        assertThat(r.minutesPlayed()).isEqualTo(90);
    }

    @Test
    void goalkeeperWithACleanSheetInAWinIsRewarded() {
        // Given a keeper who kept a clean sheet in a 1-0 win
        Player gk = player(Position.GK);

        // When rated
        PlayerRating r = rateOne(gk, List.of(), 90, 1, 0);

        // Then 6.0 + 0.6 win + 1.0 clean sheet = 7.6
        assertThat(r.rating()).isEqualTo(7.6);
    }

    @Test
    void goalkeeperConcedingFourInALossIsGradedLow() {
        // Given a keeper who conceded four in a 0-4 loss
        Player gk = player(Position.GK);

        // When rated
        PlayerRating r = rateOne(gk, List.of(), 90, 0, 4);

        // Then 6.0 - 0.4 loss - (0.30 * 4) = 4.4
        assertThat(r.rating()).isEqualTo(4.4);
    }

    @Test
    void redCardHeavilyPenalises() {
        // Given a midfielder sent off in a 1-1 draw
        Player mid = player(Position.MID);
        List<AttributedEvent> events = List.of(new AttributedEvent(mid.getId(), EventType.RED_CARD, true));

        // When rated
        PlayerRating r = rateOne(mid, events, 90, 1, 1);

        // Then 6.0 - 1.5 red = 4.5
        assertThat(r.rating()).isEqualTo(4.5);
    }

    @Test
    void yellowCardCostsPartOfAPoint() {
        // Given a midfielder booked once in a 1-1 draw
        Player mid = player(Position.MID);
        List<AttributedEvent> events = List.of(new AttributedEvent(mid.getId(), EventType.YELLOW_CARD, true));

        // When rated
        PlayerRating r = rateOne(mid, events, 90, 1, 1);

        // Then 6.0 - 0.4 yellow = 5.6
        assertThat(r.rating()).isEqualTo(5.6);
    }

    @Test
    void shortCameoIsMutedTowardSix() {
        // Given a striker who came on for 15 minutes in a 0-1 loss and did nothing
        Player att = player(Position.ATT);

        // When rated
        PlayerRating r = rateOne(att, List.of(), 15, 0, 1);

        // Then the -0.4 loss drift is halved (15/30): 6.0 - 0.2 = 5.8
        assertThat(r.rating()).isEqualTo(5.8);
    }

    @Test
    void scoringSubKeepsGoalRewardDespiteFewMinutes() {
        // Given a striker who scored the winner in a 15-minute cameo (1-0 win)
        Player att = player(Position.ATT);
        List<AttributedEvent> events = List.of(new AttributedEvent(att.getId(), EventType.GOAL, true));

        // When rated
        PlayerRating r = rateOne(att, events, 15, 1, 0);

        // Then the goal reward is NOT muted, only the +0.6 win drift is (x0.5):
        // 6.0 + 1.0 goal + (0.6 * 0.5) = 7.3
        assertThat(r.rating()).isEqualTo(7.3);
    }

    @Test
    void benchPlayersWhoNeverAppearedAreNotRated() {
        // Given a bench player with zero minutes
        Player unused = player(Position.MID);
        Player played = player(Position.MID);

        // When the side is rated
        List<PlayerRating> out = calculator.rate(
                List.of(unused, played), List.of(),
                Map.of(played.getId(), 90), 1, 0);

        // Then only the player who appeared is graded
        assertThat(out).extracting(PlayerRating::playerId).containsExactly(played.getId().toString());
    }

    @Test
    void newGoalTypesCreditGoalsLikePlainGoals() {
        // Given two identical strikers in a 3-0 win, one credited via the new set-piece goal types,
        // one via three plain GOAL events, both scoring three times
        Player viaNewTypes = player(Position.ATT);
        Player viaPlainGoals = player(Position.ATT);
        List<AttributedEvent> newTypeEvents = List.of(
                new AttributedEvent(viaNewTypes.getId(), EventType.PENALTY_GOAL, true),
                new AttributedEvent(viaNewTypes.getId(), EventType.FREE_KICK_GOAL, true),
                new AttributedEvent(viaNewTypes.getId(), EventType.CORNER_GOAL, true));
        List<AttributedEvent> plainGoalEvents = List.of(
                new AttributedEvent(viaPlainGoals.getId(), EventType.GOAL, true),
                new AttributedEvent(viaPlainGoals.getId(), EventType.GOAL, true),
                new AttributedEvent(viaPlainGoals.getId(), EventType.GOAL, true));

        // When rated
        PlayerRating withNewTypes = rateOne(viaNewTypes, newTypeEvents, 90, 3, 0);
        PlayerRating withPlainGoals = rateOne(viaPlainGoals, plainGoalEvents, 90, 3, 0);

        // Then the rating and goal tally match a plain hat-trick exactly
        assertThat(withNewTypes.rating()).isEqualTo(withPlainGoals.rating());
        assertThat(withNewTypes.goals()).isEqualTo(3);
    }

    @Test
    void aMissedPenaltyCostsHalfAPoint() {
        // Given two identical full-minutes midfielders in a 1-1 draw, one with a missed penalty
        Player withMiss = player(Position.MID);
        Player withoutMiss = player(Position.MID);
        List<AttributedEvent> missEvents = List.of(
                new AttributedEvent(withMiss.getId(), EventType.PENALTY_MISSED, true));

        // When rated
        PlayerRating rWithMiss = rateOne(withMiss, missEvents, 90, 1, 1);
        PlayerRating rWithoutMiss = rateOne(withoutMiss, List.of(), 90, 1, 1);

        // Then the missed penalty costs exactly 0.5, applied in full despite being a full 90' player
        assertThat(rWithoutMiss.rating() - rWithMiss.rating()).isEqualTo(0.5);
    }

    @Test
    void gradesAreDeterministic() {
        // Given identical inputs
        Player mid = player(Position.MID);
        List<AttributedEvent> events = List.of(new AttributedEvent(mid.getId(), EventType.GOAL, true));

        // When rated twice
        PlayerRating first = rateOne(mid, events, 90, 2, 1);
        PlayerRating second = rateOne(mid, events, 90, 2, 1);

        // Then the grades are identical
        assertThat(first.rating()).isEqualTo(second.rating());
    }
}
