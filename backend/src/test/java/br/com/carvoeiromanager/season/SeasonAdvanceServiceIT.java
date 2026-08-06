package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsCalculator;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired StandingsService standingsService;

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
        int clubCount = clubRepository.findBySaveGameId(c.id()).size(); // 31

        // When advancing to the first MATCH event
        AdvanceResponse match = advanceToNextMatch(c);

        // Then the matchday's fixtures are simulated and finance is carried
        assertThat(match.type()).isEqualTo("MATCH");
        assertThat(match.round()).isNotNull();
        assertThat(match.finance()).isNotNull();
        // New model: per-match net = gate - matchCost (sponsorship/wages/upkeep are zero per match)
        assertThat(match.finance().sponsorship()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(match.finance().wages()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(match.finance().upkeep()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(match.finance().net()).isEqualByComparingTo(
                match.finance().gate().subtract(match.finance().matchCost()));
        assertThat(match.seasonFinished()).isFalse();

        // The simulated round's fixtures are played with goals set
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().equals(match.competitionName()))
                .findFirst().orElseThrow();
        List<Fixture> round = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), competition.getId(), match.round());
        assertThat(round).allMatch(Fixture::isPlayed);
        assertThat(round).allMatch(f -> f.getHomeGoals() != null && f.getAwayGoals() != null);
    }

    @Test
    void advancingToTheEndFinishesTheSeasonAndAwardsPrize() {
        // Given a fresh career
        Career c = createCareer();
        // Scope to Catarinense: 12 members / 17 match-units (11 round-robin + 6 knockout).
        // Task 6 only materializes round-robin -> knockout (the quarterfinals); semifinals and
        // the final stay reserved (fixture-less) until Task 9 supplies tie winners via extra
        // time and penalties, so this test's assertions are scoped to what Task 6 delivers:
        // the round-robin phase plus the quarterfinals are actually played, the later two
        // knockout rounds remain empty, and the season still reaches FINISHED regardless.
        Competition catarinense = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        int catarinenseTotalRounds = catarinense.getTotalRounds(); // 17 match-units

        List<CompetitionPhase> catarinensePhases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(catarinense.getId());
        CompetitionPhase firstPhase = catarinensePhases.get(0); // Primeira Fase: ROUND_ROBIN, 8 qualifiers
        CompetitionPhase quarterFinalPhase = catarinensePhases.get(1); // KNOCKOUT, materialized by Task 6
        CompetitionPhase semifinalPhase = catarinensePhases.get(2); // KNOCKOUT, still reserved (Task 9)
        CompetitionPhase finalPhase = catarinensePhases.get(3); // KNOCKOUT, still reserved (Task 9)

        // When advancing until the season is FINISHED (guard 500 covers both competitions: 17 + 38 = 55 rounds + transfer events)
        AdvanceResponse last = null;
        List<AdvanceResponse> catarinenseMatches = new ArrayList<>();
        int guard = 0;
        SaveGameStatus status = SaveGameStatus.IN_PROGRESS;
        while (status != SaveGameStatus.FINISHED && guard++ < 500) {
            last = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(last.type()) && "Campeonato Catarinense".equals(last.competitionName())) {
                catarinenseMatches.add(last);
            }
            status = saveGameRepository.findById(c.id()).orElseThrow().getStatus();
        }

        // Then exactly catarinenseTotalRounds MATCH advances for Catarinense occurred — this
        // counts calendar match-units, not played fixtures, so it holds whether or not a given
        // round's fixtures were actually materialized.
        assertThat(catarinenseMatches).hasSize(catarinenseTotalRounds);
        assertThat(last).isNotNull();
        assertThat(last.type()).isEqualTo("MATCH");
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(java.math.BigDecimal.ZERO);

        SaveGame saved = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SaveGameStatus.FINISHED);

        // The quarterfinals were materialized (4 ties = 8 fixtures) and fully played...
        List<Fixture> quarterFinals = fixtureRepository
                .findBySaveGameIdAndPhaseId(c.id(), quarterFinalPhase.getId());
        assertThat(quarterFinals).hasSize(8);
        assertThat(quarterFinals).allMatch(Fixture::isPlayed);

        // ...but the semifinals and final are still reserved with no fixtures — that is Task 9's job.
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), semifinalPhase.getId())).isEmpty();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), finalPhase.getId())).isEmpty();

        // The 8 clubs that qualified out of the round-robin phase played 11 (round-robin) + 2
        // (quarterfinal legs) = 13 matches; the 4 that didn't qualify stop at 11.
        List<UUID> qualifiers = standingsService
                .computeStandings(saved, catarinense.getId(), firstPhase.getId(), null)
                .stream().map(StandingRow::clubId).limit(firstPhase.getQualifiers()).toList();

        List<Fixture> all = fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), catarinense.getId());
        Set<UUID> catMemberIds = competitionClubRepository.findByCompetitionId(catarinense.getId()).stream()
                .map(cc -> cc.getClubId()).collect(Collectors.toSet());
        List<Club> clubs = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> catMemberIds.contains(cl.getId())).toList();
        List<StandingRow> standings = new StandingsCalculator().compute(clubs, all);
        assertThat(standings).hasSize(12); // Catarinense has 12 clubs
        assertThat(standings.stream().filter(row -> qualifiers.contains(row.clubId())))
                .allMatch(row -> row.played() == 13);
        assertThat(standings.stream().filter(row -> !qualifiers.contains(row.clubId())))
                .allMatch(row -> row.played() == 11);

        // The whole competition's played fixtures remain zero-sum.
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
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_FINISHED);
                    assertThat(api.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void advanceBumpsLastPlayedAt() {
        // Given a fresh career (lastPlayedAt already stamped at creation)
        Career c = createCareer();
        SaveGame before = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(before.getLastPlayedAt()).isNotNull();
        Instant beforeAdvance = Instant.now();

        // When advancing once
        seasonAdvanceService.advance(c.id(), c.userId());

        // Then lastPlayedAt moved to at/after this call
        SaveGame after = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(after.getLastPlayedAt()).isAfterOrEqualTo(beforeAdvance);
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
