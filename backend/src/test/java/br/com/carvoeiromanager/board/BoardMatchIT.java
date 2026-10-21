package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionScope;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.match.LiveMatchService;
import br.com.carvoeiromanager.match.LiveMatchState;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.season.AdvanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BoardMatchIT extends BoardITSupport {

    @Autowired LiveMatchService liveMatchService;
    @Autowired LineupService lineupService;
    @Autowired PlayerRepository playerRepository;

    @Test
    void aManagedMatchMovesConfidenceByExactlyTheRowsItWrites() {
        // Given a fresh career at 60
        Career c = createCareer(true);

        // When the first managed match is played
        AdvanceResponse r = advanceToNextPlayedMatch(c);

        // Then the response's delta is the sum of the rows written, and the state agrees
        int rows = changeRepository.findBySaveGameIdOrderBySeqAsc(c.id()).stream()
                .mapToInt(BoardConfidenceChange::getDelta).sum();
        assertThat(r.board().delta()).isEqualTo(rows);
        assertThat(state(c).getConfidence()).isEqualTo(60 + rows).isEqualTo(r.board().confidence());
    }

    @Test
    void aStateFirstPhaseExitFailsTheObjectiveOnTheDayItHappensAndLaterRoundsMoveNothing() {
        // Given the Catarinense one round from the end of its first phase, with the club's results
        // so far rewritten as heavy defeats so it cannot qualify
        Career c = createCareer(true);
        Competition catarinense = competition(c, CompetitionScope.STATE);
        CompetitionPhase firstPhase = roundRobin(catarinense);
        advanceUntil(c, career -> unprocessedEventsOf(career, firstPhase) == 1);
        rigPlayedResults(c, firstPhase, false);

        // When the last first-phase round is played
        advanceUntil(c, career -> unprocessedEventsOf(career, firstPhase) == 0);

        // Then the STATE objective failed, with its own row
        assertThat(objective(c, ObjectiveKind.STATE).getOutcome()).isEqualTo(ObjectiveOutcome.FAILED);
        assertThat(changeRepository.findBySaveGameIdOrderBySeqAsc(c.id()))
                .extracting(BoardConfidenceChange::getReason).contains(BoardChangeReason.STATE_FAILED);

        // And a later Catarinense round the club is no longer in changes nothing
        for (int guard = 0; guard < 200; guard++) {
            BoardState before = state(c);
            int confidence = before.getConfidence();
            int sinceUltimatum = before.getMatchesSinceUltimatum();
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(r.type()) && catarinense.getName().equals(r.competitionName())) {
                assertThat(r.board()).isNull();
                assertThat(state(c).getConfidence()).isEqualTo(confidence);
                assertThat(state(c).getMatchesSinceUltimatum()).isEqualTo(sinceUltimatum);
                return;
            }
        }
        throw new AssertionError("No Catarinense round after the exit");
    }

    @Test
    void reachingTheStateTargetMeetsItWhileTheRunContinues() {
        // Given a quarterfinal target and a first phase rewritten as heavy wins
        Career c = createCareer(true);
        jdbc.update("UPDATE board_objectives SET target_stage = 'QUARTERFINAL' WHERE save_game_id = ? AND kind = 'STATE'",
                c.id());
        CompetitionPhase firstPhase = roundRobin(competition(c, CompetitionScope.STATE));
        advanceUntil(c, career -> unprocessedEventsOf(career, firstPhase) == 1);
        rigPlayedResults(c, firstPhase, true);

        // When the first phase ends
        advanceUntil(c, career -> unprocessedEventsOf(career, firstPhase) == 0);

        // Then the objective is met at once, with a STATE_MET row
        assertThat(objective(c, ObjectiveKind.STATE).getOutcome()).isEqualTo(ObjectiveOutcome.MET);
        assertThat(changeRepository.findBySaveGameIdOrderBySeqAsc(c.id()))
                .extracting(BoardConfidenceChange::getReason).contains(BoardChangeReason.STATE_MET);
    }

    @Test
    void aLiveMatchFinishGoesThroughTheBoardExactlyOnce() {
        // Given a career with a saved lineup
        Career c = createCareer(true);
        saveLineup(c);
        int rowsBefore = changeRepository.countBySaveGameId(c.id());

        // When a live match is played to full time and finished
        LiveMatchState s = liveMatchService.kickoff(c.id(), c.userId());
        for (int i = 0; i < 300 && !"FULL_TIME".equals(s.status()); i++) {
            s = liveMatchService.advance(c.id(), c.userId());
        }
        AdvanceResponse r = liveMatchService.finish(c.id(), c.userId());

        // Then the response carries the board's reaction, and the rows written sum to it once
        assertThat(r.board()).isNotNull();
        int written = changeRepository.findBySaveGameIdOrderBySeqAsc(c.id()).stream()
                .skip(rowsBefore).mapToInt(BoardConfidenceChange::getDelta).sum();
        assertThat(written).isEqualTo(r.board().delta());
        assertThat(state(c).getConfidence()).isEqualTo(60 + written);
    }

    private void saveLineup(Career c) {
        List<Player> bestXi = lineupService.bestXi(c.clubId(), null, null);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(c.clubId()).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-4-2", starters, bench), c.userId());
    }
}
