package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.board.BoardRules.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoardRulesTest {

    @Test
    void noUltimatumAboveThirty() {
        assertThat(BoardRules.afterMatch(31, false, 0, 10, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void anUltimatumIsIssuedAtThirty() {
        assertThat(BoardRules.afterMatch(30, false, 0, 10, true)).isEqualTo(Verdict.ISSUE_ULTIMATUM);
    }

    @Test
    void anUltimatumIsIssuedOnlyOncePerDip() {
        assertThat(BoardRules.afterMatch(30, true, 1, 10, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void theUltimatumLiftsAtFortyFive() {
        assertThat(BoardRules.afterMatch(45, true, 1, 10, true)).isEqualTo(Verdict.LIFT_ULTIMATUM);
        assertThat(BoardRules.afterMatch(44, true, 1, 10, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void sacksAtFifteenThreeMatchesAfterTheUltimatumOnceTheLeagueIsSixMatchesOld() {
        assertThat(BoardRules.afterMatch(15, true, 3, 6, true)).isEqualTo(Verdict.SACK);
    }

    @Test
    void noSackingAboveFifteen() {
        assertThat(BoardRules.afterMatch(16, true, 3, 6, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void noSackingWithinThreeMatchesOfTheUltimatum() {
        assertThat(BoardRules.afterMatch(15, true, 2, 6, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void noSackingBeforeSixLeagueMatches() {
        assertThat(BoardRules.afterMatch(15, true, 3, 5, true)).isEqualTo(Verdict.NONE);
    }

    @Test
    void theMatchThatIssuesTheUltimatumNeverSacks() {
        assertThat(BoardRules.afterMatch(5, false, 0, 30, true)).isEqualTo(Verdict.ISSUE_ULTIMATUM);
    }

    @Test
    void withoutSackingTheSackIsAverted() {
        assertThat(BoardRules.afterMatch(15, true, 3, 6, false)).isEqualTo(Verdict.SACKING_AVERTED);
    }

    @Test
    void theSeasonEndReviewSacksBelowTwentyFive() {
        assertThat(BoardRules.atSeasonEnd(24, true)).isEqualTo(Verdict.SACK);
        assertThat(BoardRules.atSeasonEnd(25, true)).isEqualTo(Verdict.NONE);
        assertThat(BoardRules.atSeasonEnd(24, false)).isEqualTo(Verdict.SACKING_AVERTED);
    }
}
