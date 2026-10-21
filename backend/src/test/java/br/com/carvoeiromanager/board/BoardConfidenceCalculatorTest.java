package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.board.BoardConfidenceCalculator.MatchInput;
import br.com.carvoeiromanager.board.BoardConfidenceCalculator.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static br.com.carvoeiromanager.board.BoardChangeReason.HEAVY_DEFEAT;
import static br.com.carvoeiromanager.board.BoardChangeReason.MATCH_RESULT;
import static br.com.carvoeiromanager.board.BoardChangeReason.NEGATIVE_BALANCE;
import static br.com.carvoeiromanager.board.BoardChangeReason.TABLE_POSITION;
import static org.assertj.core.api.Assertions.assertThat;

class BoardConfidenceCalculatorTest {

    private static MatchInput match(double own, double opp, boolean home, int gf, int ga) {
        return new MatchInput(own, opp, home, gf, ga, false, null, null, false);
    }

    @Test
    void homeDrawBetweenEqualsCostsOne() {
        // expected = 1.35 + 0.25 = 1.60; (1 − 1.60)·2 = −1.2 → −1
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, true, 1, 1)))
                .containsExactly(new Part(MATCH_RESULT, -1));
    }

    @Test
    void awayDrawBetweenEqualsIsNeutral() {
        // expected = 1.10; (1 − 1.10)·2 = −0.2 → 0, so no row at all
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, false, 0, 0))).isEmpty();
    }

    @Test
    void awayWinAgainstAMuchStrongerSideIsWorthFive() {
        // diff −10: expected = 1.35 − 0.5 − 0.25 = 0.60; (3 − 0.60)·2 = 4.8 → 5
        assertThat(BoardConfidenceCalculator.afterMatch(match(45, 55, false, 1, 0)))
                .containsExactly(new Part(MATCH_RESULT, 5));
    }

    @Test
    void homeLossToAMuchWeakerSideCostsFour() {
        // diff +10: expected = 1.35 + 0.5 + 0.25 = 2.10; (0 − 2.10)·2 = −4.2 → −4
        assertThat(BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 1)))
                .containsExactly(new Part(MATCH_RESULT, -4));
    }

    @Test
    void expectationIsCappedForAnOverwhelmingFavourite() {
        // diff +40: 1.35 + 2.0 + 0.25 = 3.6 → capped at 2.70; (0 − 2.70)·2 = −5.4 → −5
        assertThat(BoardConfidenceCalculator.afterMatch(match(80, 40, true, 0, 1)))
                .containsExactly(new Part(MATCH_RESULT, -5));
    }

    @Test
    void aFourGoalDefeatAddsAHeavyDefeatRow() {
        assertThat(BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 4)))
                .containsExactly(new Part(MATCH_RESULT, -4), new Part(HEAVY_DEFEAT, -3));
    }

    @Test
    void theTotalIsClampedAtMinusEightTrimmingFromTheLastRow() {
        // −5 result, −3 heavy defeat, −1 table, −1 balance = −10: the balance row goes, then the table row
        MatchInput in = new MatchInput(80, 40, true, 0, 4, false, 20, 4, true);
        assertThat(BoardConfidenceCalculator.afterMatch(in))
                .containsExactly(new Part(MATCH_RESULT, -5), new Part(HEAVY_DEFEAT, -3));
    }

    @Test
    void stateMatchesCountHalf() {
        // diff +2 at home: expected 1.70; (0 − 1.70)·2 = −3.4 → −3, halved −1.5, Math.round(−1.5) = −1
        MatchInput in = new MatchInput(51, 49, true, 0, 1, true, null, null, false);
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(MATCH_RESULT, -1));
    }

    @ParameterizedTest(name = "position {0} against reference {1} → {2}")
    @CsvSource({"4, 4, 1", "1, 4, 1", "8, 4, 0", "9, 4, -1"})
    void trajectoryNudgesByTablePosition(int position, int reference, int expected) {
        MatchInput in = new MatchInput(50, 50, false, 0, 0, false, position, reference, false);
        int got = BoardConfidenceCalculator.afterMatch(in).stream()
                .filter(p -> p.reason() == TABLE_POSITION).mapToInt(Part::delta).sum();
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void aNegativeBalanceCostsOnePerMatch() {
        MatchInput in = new MatchInput(50, 50, false, 0, 0, false, null, null, true);
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(NEGATIVE_BALANCE, -1));
    }

    @ParameterizedTest(name = "finished {0}º against target {1} (zone={2}) → {3}")
    @CsvSource({
            "1, 8, true, 20",
            "4, 8, true, 20",
            "5, 8, true, 12",
            "8, 8, true, 12",
            "9, 8, true, -12",
            "11, 8, true, -12",
            "12, 8, true, -25",
            "16, 16, true, 12",
            "17, 10, true, -35",
            "17, 14, false, -12",
            "20, 14, false, -25",
    })
    void leaguePositionResolution(int finalPos, int target, boolean zone, int expected) {
        assertThat(BoardConfidenceCalculator.leaguePositionDelta(finalPos, target, zone, 20)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "reached {0} against target {1} → {2}")
    @CsvSource({
            "SEMIFINAL, SEMIFINAL, 12",
            "CHAMPION, QUARTERFINAL, 12",
            "QUARTERFINAL, SEMIFINAL, -12",
            "QUARTERFINAL, FINAL, -25",
            "NOT_QUALIFIED, QUARTERFINAL, -12",
            "NOT_QUALIFIED, SEMIFINAL, -25",
    })
    void leagueStageResolution(Stage reached, Stage target, int expected) {
        assertThat(BoardConfidenceCalculator.leagueStageDelta(reached, target)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} regresses to {1}")
    @CsvSource({"60, 60", "100, 80", "81, 70", "45, 53", "0, 30", "59, 60", "61, 60"})
    void regressionMovesHalfwayTowardSixtyRoundingTowardSixty(int before, int after) {
        assertThat(BoardConfidenceCalculator.regress(before)).isEqualTo(after);
    }

    @Test
    void objectiveDeltas() {
        assertThat(BoardConfidenceCalculator.stateDelta(true)).isEqualTo(6);
        assertThat(BoardConfidenceCalculator.stateDelta(false)).isEqualTo(-8);
        assertThat(BoardConfidenceCalculator.financeDelta(true)).isEqualTo(4);
        assertThat(BoardConfidenceCalculator.financeDelta(false)).isEqualTo(-10);
        assertThat(BoardConfidenceCalculator.TITLE_WON).isEqualTo(4);
    }

    @Test
    void confidenceIsClampedToZeroToHundred() {
        assertThat(BoardConfidenceCalculator.clamp(-5)).isZero();
        assertThat(BoardConfidenceCalculator.clamp(105)).isEqualTo(100);
    }
}
