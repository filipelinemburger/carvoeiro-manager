package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.CreateSaveGameRequest;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.CompetitionScope;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.BackgroundCompetitionService;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Shared career set-up and season driving for the board's integration tests. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
abstract class BoardITSupport {

    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired BackgroundCompetitionService backgroundCompetitionService;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired BoardService boardService;
    @Autowired BoardStateRepository stateRepository;
    @Autowired BoardObjectiveRepository objectiveRepository;
    @Autowired BoardConfidenceChangeRepository changeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    Career createCareer(boolean sackingEnabled) {
        User user = User.create("board_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(),
                saveGameService.createCareer(user.getId(), new CreateSaveGameRequest("Técnico", "Brasil", sackingEnabled)));
    }

    SaveGame save(Career c) {
        return saveGameRepository.findById(c.id()).orElseThrow();
    }

    BoardState state(Career c) {
        return stateRepository.findById(c.id()).orElseThrow();
    }

    Competition competition(Career c, CompetitionScope scope) {
        return competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getScope() == scope)
                .filter(comp -> backgroundCompetitionService.isManaged(comp.getId(), c.clubId()))
                .findFirst().orElseThrow();
    }

    List<BoardObjective> objectives(Career c) {
        return objectiveRepository.findBySaveGameIdAndSeasonYear(c.id(), save(c).getSeasonYear());
    }

    BoardObjective objective(Career c, ObjectiveKind kind) {
        return objectives(c).stream().filter(o -> o.getKind() == kind).findFirst().orElseThrow();
    }

    List<NewsItem> news(Career c, String type) {
        return newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id()).stream()
                .filter(i -> type.equals(i.getType())).toList();
    }

    AdvanceResponse payload(NewsItem item) {
        return objectMapper.readValue(item.getPayload(), AdvanceResponse.class);
    }

    void setBoard(Career c, int confidence, boolean ultimatum, int matchesSinceUltimatum) {
        jdbc.update("UPDATE board_states SET confidence = ?, ultimatum_active = ?, matches_since_ultimatum = ? "
                + "WHERE save_game_id = ?", confidence, ultimatum, matchesSinceUltimatum, c.id());
    }

    /** Advances until {@code stop} holds for the save, or the season is no longer in progress. */
    void advanceUntil(Career c, Predicate<Career> stop) {
        for (int guard = 0; guard < 600; guard++) {
            if (stop.test(c) || save(c).getStatus() != SaveGameStatus.IN_PROGRESS) {
                return;
            }
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        throw new IllegalStateException("advanceUntil: guard exhausted");
    }

    /** Advances to the next MATCH response the managed club played in (board non-null). */
    AdvanceResponse advanceToNextPlayedMatch(Career c) {
        for (int guard = 0; guard < 600; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(r.type()) && r.board() != null) {
                return r;
            }
        }
        throw new IllegalStateException("No played managed match within guard");
    }

    /** Advances everything but the managed club's last match of the season. */
    void advanceToLastManagedMatch(Career c) {
        advanceUntil(c, career -> unprocessedManagedMatches(career) <= 1);
    }

    int unprocessedManagedMatches(Career c) {
        return (int) seasonEventRepository.findBySaveGameIdAndProcessedFalse(c.id()).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> backgroundCompetitionService.isManaged(e.getCompetitionId(), c.clubId()))
                .count();
    }

    long playedFixtures(Career c, Competition competition) {
        return fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), competition.getId()).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> c.clubId().equals(f.getHomeClubId()) || c.clubId().equals(f.getAwayClubId()))
                .count();
    }

    CompetitionPhase roundRobin(Competition competition) {
        return competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competition.getId()).getFirst();
    }

    long unprocessedEventsOf(Career c, CompetitionPhase phase) {
        return seasonEventRepository.findBySaveGameIdAndProcessedFalse(c.id()).stream()
                .map(SeasonEvent::getPhaseId)
                .filter(phase.getId()::equals)
                .count();
    }

    /** Rewrites every played managed fixture of the phase as a big win (or loss) for the club. */
    void rigPlayedResults(Career c, CompetitionPhase phase, boolean win) {
        for (Fixture f : fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phase.getId())) {
            boolean home = c.clubId().equals(f.getHomeClubId());
            if (!f.isPlayed() || !(home || c.clubId().equals(f.getAwayClubId()))) {
                continue;
            }
            boolean homeWins = home == win;
            f.recordResult(homeWins ? 9 : 0, homeWins ? 0 : 9);
            fixtureRepository.save(f);
        }
    }
}
