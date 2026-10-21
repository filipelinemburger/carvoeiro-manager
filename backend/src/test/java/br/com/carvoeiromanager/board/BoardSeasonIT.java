package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonRolloverService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionScope;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardSeasonIT extends BoardITSupport {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired StandingsService standingsService;
    @Autowired FinanceCalculator financeCalculator;

    @Test
    void aFullSeasonIsReviewedThenTheNewSeasonRegressesAndSetsNewObjectives() {
        // Given a career played to the end of its season (sacking off, so the ending is FINISHED)
        Career c = createCareer(false);
        int firstSeason = save(c).getSeasonYear();
        advanceUntil(c, career -> false);

        // Then the review ran once, settled every objective and finished the season
        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.FINISHED);
        assertThat(news(c, "BOARD_REVIEW")).hasSize(1);
        assertThat(objectives(c)).hasSize(3).noneMatch(BoardObjective::isPending);

        // When confidence is high and the season rolls over
        setBoard(c, 81, false, 0);
        rolloverService.rollover(c.id(), c.userId());

        // Then confidence moved halfway back toward 60, logged as the new season's only change
        assertThat(state(c).getConfidence()).isEqualTo(70);
        List<BoardConfidenceChange> changes = changeRepository.findBySaveGameIdOrderBySeqAsc(c.id());
        assertThat(changes).singleElement().satisfies(ch -> {
            assertThat(ch.getReason()).isEqualTo(BoardChangeReason.NEW_SEASON);
            assertThat(ch.getDelta()).isEqualTo(-11);
        });

        // And the new season has its own objectives, announced once, while last season's are kept
        assertThat(objectives(c)).hasSize(3).allMatch(BoardObjective::isPending);
        assertThat(objectiveRepository.findBySaveGameIdAndSeasonYear(c.id(), firstSeason))
                .hasSize(3).noneMatch(BoardObjective::isPending);
        assertThat(news(c, "BOARD_OBJECTIVES")).hasSize(1);
    }

    @Test
    void theFinanceObjectiveCountsTheSeasonEndPrize() {
        // Given the last managed match to play, and a deficit the prize will cover but the match
        // day will not. The prize pays R$500k per place from the bottom; the last round moves the
        // club a place or two at most, and its own gate is under R$1M. So a deficit three places'
        // worth smaller than today's prize stays negative through the match day and positive after
        // the prize — provided today's prize is big enough to leave that margin (16th or better).
        Career c = createCareer(false);
        advanceToLastManagedMatch(c);
        Competition league = competition(c, CompetitionScope.NATIONAL);
        List<StandingRow> table = standingsService.computeStandings(save(c), league.getId(), null);
        int position = table.stream().map(StandingRow::clubId).toList().indexOf(c.clubId()) + 1;
        Assumptions.assumeTrue(position <= 15, "needs a prize big enough to leave the margin; club is " + position + "º");
        BigDecimal prizeNow = financeCalculator.prizeMoney(position, table.size());
        BigDecimal deficit = prizeNow.subtract(new BigDecimal("1500000.00")).negate();
        jdbc.update("UPDATE clubs SET balance = ? WHERE id = ?", deficit, c.clubId());

        // When the last match is played
        AdvanceResponse last = advanceToNextPlayedMatch(c);

        // Then the prize (paid on this round) took the balance positive, and the board saw it
        BigDecimal prize = last.finance().prize();
        BigDecimal finalBalance = last.finance().balance();
        assertThat(prize).isPositive();
        assertThat(finalBalance.subtract(prize)).as("balance before the prize").isLessThanOrEqualTo(BigDecimal.ZERO);
        assertThat(finalBalance).isPositive();
        assertThat(objective(c, ObjectiveKind.FINANCE).getOutcome()).isEqualTo(ObjectiveOutcome.MET);
    }

    @Test
    void aBadSeasonEndsInASackingAtTheReview() {
        // Given the last managed match to play, no confidence left, an unreachable league target
        // and a negative balance (so no objective can rescue the review). Sacking is switched on
        // only now: a simulated season can legitimately sack the manager before its last match.
        Career c = createCareer(false);
        advanceToLastManagedMatch(c);
        jdbc.update("UPDATE board_states SET sacking_enabled = TRUE WHERE save_game_id = ?", c.id());
        setBoard(c, 0, false, 0);
        jdbc.update("UPDATE board_objectives SET target_position = 1 WHERE save_game_id = ? AND kind = 'LEAGUE'", c.id());
        jdbc.update("UPDATE clubs SET balance = ? WHERE id = ?", new BigDecimal("-100000000.00"), c.clubId());

        // When it is played
        AdvanceResponse last = advanceToNextPlayedMatch(c);

        // Then the review sacked the manager, the MATCH response says so, and the save is frozen
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.board().sacked()).isTrue();
        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.SACKED);
        assertThat(news(c, "BOARD_REVIEW")).hasSize(1);
        assertThatThrownBy(() -> rolloverService.rollover(c.id(), c.userId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.MANAGER_SACKED));
    }
}
