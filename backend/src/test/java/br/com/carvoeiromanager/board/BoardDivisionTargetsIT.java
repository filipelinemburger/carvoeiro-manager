package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * League targets in the divisions the club cannot reach yet (promotion/relegation is unbuilt).
 * The test moves the managed club's membership by hand, which is enough to exercise objective
 * setting; resolution in those divisions is covered by BoardConfidenceCalculatorTest.
 */
@TestPropertySource(properties = "carvoeiro.seed.competitions=")
class BoardDivisionTargetsIT extends BoardITSupport {

    @Test
    void aClubInSerieAIsSetASerieATarget() {
        Career c = moveInto("Série A");
        BoardObjective league = objective(c, ObjectiveKind.LEAGUE);
        assertThat(league.getTier()).isEqualTo(1);
        assertThat(league.getTargetPosition()).isIn(4, 6, 12, 16);
        assertThat(league.getTargetStage()).isNull();
    }

    @Test
    void aClubInSerieCIsSetAStageOrAFirstPhaseTarget() {
        Career c = moveInto("Série C");
        BoardObjective league = objective(c, ObjectiveKind.LEAGUE);
        assertThat(league.getTier()).isEqualTo(3);
        if (league.getTargetStage() != null) {
            assertThat(league.getTargetStage()).isIn(Stage.FINAL, Stage.SEMIFINAL, Stage.QUARTERFINAL);
            assertThat(league.getTargetPosition()).isNull();
        } else {
            assertThat(league.getTargetPosition()).isEqualTo(14);
        }
    }

    /** Swaps the managed club into {@code division} in place of one of its members, keeping 20. */
    private Career moveInto(String division) {
        Career c = createCareer(true);
        Competition serieB = competition(c, CompetitionScope.NATIONAL);
        Competition target = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().equals(division)).findFirst().orElseThrow();
        jdbc.update("DELETE FROM competition_club WHERE id = (SELECT id FROM competition_club "
                + "WHERE competition_id = ? LIMIT 1)", target.getId());
        jdbc.update("UPDATE competition_club SET competition_id = ? WHERE club_id = ? AND competition_id = ?",
                target.getId(), c.clubId(), serieB.getId());
        jdbc.update("DELETE FROM board_objectives WHERE save_game_id = ?", c.id());

        boardService.setSeasonObjectives(save(c));
        return c;
    }
}
