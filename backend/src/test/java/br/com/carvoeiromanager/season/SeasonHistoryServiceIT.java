package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonHistoryServiceIT {

    @Autowired SeasonHistoryService historyService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("hist_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private int finishAndRollOver(Career c) {
        int year = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow().getSeasonYear();
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        rolloverService.rollover(c.id(), c.userId());
        return year;
    }

    @Test
    void historyListsArchivedSeasonsNewestFirst() {
        // Given a career rolled over once
        Career c = createCareer();
        int firstYear = finishAndRollOver(c);

        // When listing history
        List<SeasonSummaryResponse> history = historyService.getHistory(c.id(), c.userId());

        // Then archived seasons appear: 2 competitions per year → 2 summary entries
        assertThat(history).hasSize(2);
        // Both are for the rolled-over year
        assertThat(history).allMatch(s -> s.seasonYear() == firstYear);
        // The Catarinense summary has a valid managed position (10-club competition)
        SeasonSummaryResponse catSummary = history.stream()
                .filter(s -> s.competitionName().contains("Catarinense"))
                .findFirst().orElseThrow();
        assertThat(catSummary.championClubName()).isNotBlank();
        assertThat(catSummary.managedClubPosition()).isBetween(1, 10);
        assertThat(catSummary.topScorerGoals()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void detailReturnsTheFullArchivedTableAndScorers() {
        // Given a career rolled over once
        Career c = createCareer();
        int year = finishAndRollOver(c);

        // When fetching the season detail
        SeasonDetailResponse detail = historyService.getSeason(c.id(), year, c.userId());

        // Then the full table (10 clubs, ordered) and scorers are present
        assertThat(detail.seasonYear()).isEqualTo(year);
        assertThat(detail.table()).hasSize(10);
        assertThat(detail.table().get(0).position()).isEqualTo(1);
        assertThat(detail.topScorers()).isNotEmpty();
    }

    @Test
    void unknownSeasonYearIsNotFound() {
        Career c = createCareer();
        assertThatThrownBy(() -> historyService.getSeason(c.id(), 1999, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_NOT_FOUND);
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void foreignUserCannotReadHistoryOrDetail() {
        Career owner = createCareer();
        Career intruder = createCareer();
        int year = finishAndRollOver(owner);
        assertThatThrownBy(() -> historyService.getHistory(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> historyService.getSeason(owner.id(), year, intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
