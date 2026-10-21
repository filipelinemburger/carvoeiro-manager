package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoardObjectivesIT extends BoardITSupport {

    @Test
    void aNewCareerStartsAtSixtyWithThreeObjectivesAndNoGrace() {
        // When a career is created
        Career c = createCareer(true);

        // Then the board starts neutral, may sack, and has no grace season
        BoardState state = state(c);
        assertThat(state.getConfidence()).isEqualTo(60);
        assertThat(state.isSackingEnabled()).isTrue();
        assertThat(state.getGraceSeasonYear()).isNull();

        // And it set a league (Série B, a position), a state (a stage) and a finance objective
        assertThat(objective(c, ObjectiveKind.LEAGUE).getTier()).isEqualTo(2);
        assertThat(objective(c, ObjectiveKind.LEAGUE).getTargetPosition()).isIn(4, 8, 10, 16);
        assertThat(objective(c, ObjectiveKind.STATE).getTargetStage()).isNotNull();
        assertThat(objective(c, ObjectiveKind.FINANCE).getOutcome()).isEqualTo(ObjectiveOutcome.PENDING);

        // And announced them in one news item carrying all three
        List<NewsItem> items = news(c, "BOARD_OBJECTIVES");
        assertThat(items).hasSize(1);
        AdvanceResponse payload = payload(items.getFirst());
        assertThat(payload.board().objectives()).extracting(BoardObjectiveResponse::kind)
                .containsExactly("LEAGUE", "STATE", "FINANCE");
        assertThat(payload.date()).isNotNull();
    }

    @Test
    void sackingCanBeSwitchedOffAtCreation() {
        Career c = createCareer(false);
        assertThat(state(c).isSackingEnabled()).isFalse();
    }

    @Test
    void aCareerThatPredatesTheBoardGetsAGraceSeasonOnFirstTouch() {
        // Given a career whose board rows do not exist (created before the feature)
        Career c = createCareer(true);
        jdbc.update("DELETE FROM board_states WHERE save_game_id = ?", c.id());
        jdbc.update("DELETE FROM board_objectives WHERE save_game_id = ?", c.id());

        // When the board is first touched
        BoardState state = boardService.ensureState(save(c));

        // Then this season is a grace season, sacking stays enabled, and objectives exist again
        assertThat(state.getGraceSeasonYear()).isEqualTo(save(c).getSeasonYear());
        assertThat(state.isSackingEnabled()).isTrue();
        assertThat(state.canSack(save(c).getSeasonYear())).isFalse();
        assertThat(objectives(c)).hasSize(3);
    }
}
