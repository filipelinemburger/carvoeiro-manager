package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.board.BoardConfidenceCalculator.MatchInput;
import br.com.carvoeiromanager.board.BoardConfidenceCalculator.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static br.com.carvoeiromanager.board.BoardChangeReason.HEAVY_DEFEAT;
import static br.com.carvoeiromanager.board.BoardChangeReason.MATCH_RESULT;
import static br.com.carvoeiromanager.board.BoardChangeReason.NEGATIVE_BALANCE;
import static br.com.carvoeiromanager.board.BoardChangeReason.TABLE_POSITION;
import static org.assertj.core.api.Assertions.assertThat;

class BoardConfidenceCalculatorTest {

    private static MatchInput match(double own, double opp, boolean home, int gf, int ga) {
        return new MatchInput(own, opp, home, gf, ga, false, null, null, false);
    }

    private static int total(List<Part> parts) {
        return parts.stream().mapToInt(Part::delta).sum();
    }

    @Test
    void homeDrawBetweenEqualsCostsTwo() {
        // expected = 1.35 + 0.30 = 1.65; (1 − 1.65)·3 = −1.95 → −2
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, true, 1, 1)))
                .containsExactly(new Part(MATCH_RESULT, -2));
    }

    @Test
    void awayDrawBetweenEqualsIsNeutral() {
        // expected = 1.05; (1 − 1.05)·3 = −0.15 → 0, so no row at all
        assertThat(BoardConfidenceCalculator.afterMatch(match(50, 50, false, 0, 0))).isEmpty();
    }

    @Test
    void awayWinAgainstAMuchStrongerSideIsWorthEight() {
        // diff −10: expected = clamp(1.35 − 0.8 − 0.3) = 0.30 (floor); (3 − 0.30)·3 = 8.1 → 8
        assertThat(total(BoardConfidenceCalculator.afterMatch(match(45, 55, false, 1, 0)))).isEqualTo(8);
    }

    @Test
    void homeLossToAMuchWeakerSideCostsSeven() {
        // diff +10: expected = 1.35 + 0.8 + 0.3 = 2.45; (0 − 2.45)·3 = −7.35 → −7
        assertThat(BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 1)))
                .containsExactly(new Part(MATCH_RESULT, -7));
    }

    @Test
    void aFourGoalDefeatAddsAHeavyDefeatRowAndTheTotalIsClampedAtMinusEight() {
        List<Part> parts = BoardConfidenceCalculator.afterMatch(match(55, 45, true, 0, 4));
        // −7 −3 = −10 → clamped to −8; the trim comes off the last row written
        assertThat(parts).containsExactly(new Part(MATCH_RESULT, -7), new Part(HEAVY_DEFEAT, -1));
    }

    @Test
    void stateMatchesCountHalf() {
        MatchInput in = new MatchInput(55, 45, true, 0, 1, true, null, null, false);
        // round(−7.35) = −7, halved = −3.5, and Math.round(−3.5) = −3 (half rounds up, toward zero here)
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(MATCH_RESULT, -3));
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

    @Test
    void aClampedWinKeepsTheResultRowAndTrimsTheBonusesAfterIt() {
        // +8 result, +1 table: 9 → 8, so the table row is trimmed away entirely
        MatchInput in = new MatchInput(45, 55, false, 1, 0, false, 1, 4, false);
        assertThat(BoardConfidenceCalculator.afterMatch(in)).containsExactly(new Part(MATCH_RESULT, 8));
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
