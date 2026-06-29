package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsCalculator;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonAdvanceServiceIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
    }

    private Career createCareer() {
        User user = User.create("advance_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH response is produced (skipping any TRANSFER stops). */
    private AdvanceResponse advanceToNextMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(r.type())) {
                return r;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void advancingPopsEventsInDateOrderAndAdvancesTheCurrentDate() {
        // Given a fresh career: the very first event is a TRANSFER stop on the season start
        // (the pre-season window opens on Jan 15, sequence 1 after the day's MATCH? no:
        // events are ordered by date asc then sequence asc; the first match unit and the
        // first transfer stop both land on the season start, MATCH has sequence 0).
        Career c = createCareer();
        SeasonEvent first = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(c.id());
        assertThat(first).isNotNull();
        LocalDate firstDate = first.getEventDate();

        // When advancing once
        AdvanceResponse response = seasonAdvanceService.advance(c.id(), c.userId());

        // Then the save's current date moved to the popped event's date
        SaveGame saved = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(saved.getCurrentDate()).isEqualTo(firstDate);
        assertThat(response.date()).isEqualTo(firstDate.toString());
        assertThat(response.type()).isIn("MATCH", "TRANSFER");

        // And the popped event is now processed
        SeasonEvent reloaded = seasonEventRepository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.isProcessed()).isTrue();
    }

    @Test
    void aMatchAdvanceSimulatesItsRoundAndCarriesFinance() {
        // Given a fresh career
        Career c = createCareer();
        int clubCount = clubRepository.findBySaveGameId(c.id()).size(); // 10

        // When advancing to the first MATCH event
        AdvanceResponse match = advanceToNextMatch(c);

        // Then the matchday's fixtures are simulated and finance is carried
        assertThat(match.type()).isEqualTo("MATCH");
        assertThat(match.competitionName()).isEqualTo("Campeonato Catarinense");
        assertThat(match.round()).isNotNull();
        assertThat(match.results()).hasSize(clubCount / 2); // 5 fixtures per Catarinense round
        assertThat(match.finance()).isNotNull();
        assertThat(match.finance().net()).isEqualByComparingTo(
                match.finance().gate().add(match.finance().sponsorship())
                        .subtract(match.finance().wages()).subtract(match.finance().upkeep()));
        assertThat(match.seasonFinished()).isFalse();

        // The simulated round's fixtures are played with goals set
        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        List<Fixture> round = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competition.getId(), match.round());
        assertThat(round).allMatch(Fixture::isPlayed);
        assertThat(round).allMatch(f -> f.getHomeGoals() != null && f.getAwayGoals() != null);
    }

    @Test
    void advancingToTheEndFinishesTheSeasonAndAwardsPrize() {
        // Given a fresh career
        Career c = createCareer();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        int totalRounds = competition.getTotalRounds(); // 18
        int clubCount = clubRepository.findBySaveGameId(c.id()).size(); // 10

        // When advancing until the season is FINISHED
        AdvanceResponse last = null;
        List<AdvanceResponse> matches = new ArrayList<>();
        int guard = 0;
        SaveGameStatus status = SaveGameStatus.IN_PROGRESS;
        while (status != SaveGameStatus.FINISHED && guard++ < 500) {
            last = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(last.type())) {
                matches.add(last);
            }
            status = saveGameRepository.findById(c.id()).orElseThrow().getStatus();
        }

        // Then exactly totalRounds MATCH advances occurred and the last one finishes the season
        assertThat(matches).hasSize(totalRounds);
        assertThat(last).isNotNull();
        assertThat(last.type()).isEqualTo("MATCH");
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(java.math.BigDecimal.ZERO);

        SaveGame saved = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SaveGameStatus.FINISHED);

        // Every fixture is played and the table is zero-sum
        List<Fixture> all = fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), competition.getId());
        assertThat(all).allMatch(Fixture::isPlayed);
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        List<StandingRow> standings = new StandingsCalculator().compute(clubs, all);
        assertThat(standings).hasSize(clubCount);
        assertThat(standings).allMatch(row -> row.played() == totalRounds);
        int totalGF = standings.stream().mapToInt(StandingRow::goalsFor).sum();
        int totalGA = standings.stream().mapToInt(StandingRow::goalsAgainst).sum();
        assertThat(totalGF).isEqualTo(totalGA);
    }

    @Test
    void advancingAFinishedSeasonThrowsConflict() {
        // Given a fully advanced career
        Career c = createCareer();
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }

        // When advancing again, then a 409 SEASON_FINISHED is thrown
        assertThatThrownBy(() -> seasonAdvanceService.advance(c.id(), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SEASON_FINISHED");
                    assertThat(api.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void advancingForAForeignUserIsNotFound() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> seasonAdvanceService.advance(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
