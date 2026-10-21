package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardControllerIT extends BoardITSupport {

    @Autowired BoardController controller;

    @Test
    void aFreshCareerShowsSixtyAndThreeObjectives() {
        Career c = createCareer(true);

        BoardResponse board = controller.get(c.id(), c.userId());

        assertThat(board.confidence()).isEqualTo(60);
        assertThat(board.sackingEnabled()).isTrue();
        assertThat(board.graceSeason()).isFalse();
        assertThat(board.objectives()).extracting(BoardObjectiveResponse::kind)
                .containsExactly("LEAGUE", "STATE", "FINANCE");
        BoardObjectiveResponse league = board.objectives().getFirst();
        assertThat(league.progress()).as("no league match played yet").isNull();
        assertThat(league.competitionName()).isEqualTo("Série B");
        assertThat(board.objectives().getLast().progress()).isEqualTo("ON_TRACK");
    }

    @Test
    void recentChangesArriveNewestFirstWithTheirPayload() {
        Career c = createCareer(true);
        advanceToNextPlayedMatch(c);
        advanceToNextPlayedMatch(c);

        BoardResponse board = controller.get(c.id(), c.userId());

        assertThat(board.recentChanges()).hasSizeLessThanOrEqualTo(BoardService.RECENT_CHANGES);
        board.recentChanges().stream().filter(ch -> ch.reason().equals("MATCH_RESULT")).findFirst()
                .ifPresent(ch -> assertThat(ch.payload()).containsKeys("opponent", "score", "home"));
    }

    @Test
    void anotherUsersSaveIsNotFound() {
        Career c = createCareer(true);
        User stranger = User.create("stranger_" + UUID.randomUUID(), "hash");
        userRepository.save(stranger);

        assertThatThrownBy(() -> controller.get(c.id(), stranger.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SAVE_GAME_NOT_FOUND));
    }

    @Test
    void aSackedSaveCanStillBeRead() {
        Career c = createCareer(true);
        jdbc.update("UPDATE save_games SET status = 'SACKED' WHERE id = ?", c.id());

        assertThat(save(c).getStatus()).isEqualTo(SaveGameStatus.SACKED);
        assertThat(controller.get(c.id(), c.userId()).confidence()).isEqualTo(60);
    }
}
