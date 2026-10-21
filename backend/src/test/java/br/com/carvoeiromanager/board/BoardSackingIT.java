package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.CompetitionScope;
import br.com.carvoeiromanager.match.LiveMatchService;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonRolloverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardSackingIT extends BoardITSupport {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SeasonRolloverService rolloverService;

    /**
     * Six league matches in, at rock bottom, two matches into an ultimatum: whatever the next
     * match's result (+8 at most, plus a state objective at most), confidence stays at or below 15
     * and the counter reaches 3.
     */
    private Career careerOnTheBrink(boolean sackingEnabled) {
        Career c = createCareer(sackingEnabled);
        advanceUntil(c, career -> playedFixtures(career, competition(career, CompetitionScope.NATIONAL)) >= 6);
        setBoard(c, 0, true, 2);
        return c;
    }

    @Test
    void aManagerOnAnUltimatumIsSackedAndTheSaveFreezes() {
        Career c = careerOnTheBrink(true);

        AdvanceResponse r = advanceToNextPlayedMatch(c);

        assertThat(r.board().sacked()).isTrue();
        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.SACKED);
        assertThat(news(c, "BOARD_SACKED")).hasSize(1);

        assertSacked(() -> seasonAdvanceService.advance(c.id(), c.userId()));
        assertSacked(() -> liveMatchService.kickoff(c.id(), c.userId()));
        assertSacked(() -> rolloverService.rollover(c.id(), c.userId()));
    }

    @Test
    void withSackingDisabledTheSackingIsAverted() {
        Career c = careerOnTheBrink(false);

        AdvanceResponse r = advanceToNextPlayedMatch(c);

        assertThat(r.board().sacked()).isFalse();
        assertThat(r.board().sackingAverted()).isTrue();
        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
        assertThat(news(c, "BOARD_SACKING_AVERTED")).hasSize(1);
        assertThat(state(c).getConfidence()).isGreaterThanOrEqualTo(BoardRules.AVERTED_FLOOR);
    }

    @Test
    void inTheGraceSeasonTheSackingIsAverted() {
        Career c = careerOnTheBrink(true);
        jdbc.update("UPDATE board_states SET grace_season_year = ? WHERE save_game_id = ?",
                save(c).getSeasonYear(), c.id());

        advanceToNextPlayedMatch(c);

        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
        assertThat(news(c, "BOARD_SACKING_AVERTED")).hasSize(1);
    }

    @Test
    void anUltimatumIsIssuedOnceAndAnnounced() {
        Career c = createCareer(true);
        setBoard(c, 0, false, 0);

        AdvanceResponse r = advanceToNextPlayedMatch(c);

        assertThat(r.board().ultimatumIssued()).isTrue();
        assertThat(state(c).isUltimatumActive()).isTrue();
        assertThat(state(c).getMatchesSinceUltimatum()).isZero();
        assertThat(news(c, "BOARD_ULTIMATUM")).hasSize(1);
    }

    private static void assertSacked(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.MANAGER_SACKED));
    }
}
