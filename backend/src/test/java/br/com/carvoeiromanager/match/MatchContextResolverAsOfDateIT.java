package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the CRITICAL review finding on Task 9: {@code MatchContextResolver}'s
 * {@code leaguePosition}/{@code clubCount} must be bound to the fixture's {@code matchDate}, just
 * like {@code recentFormPoints} already is. Without that bound, {@code StandingsService} reads
 * whatever is currently persisted with no regard for match order, so a club's position can already
 * include the very match (or a same-round peer, sharing the same date) whose crowd is being
 * priced — a home side that wins big climbs the table and therefore draws a bigger crowd to that
 * SAME match, and the answer depends on what has/hasn't been recorded yet, which differs between
 * {@code LiveMatchService.finish} and {@code FixtureSimulator.simulateRound}.
 *
 * <p>This does not drive the two full production call sites (that needs a complete lineup/tactics/
 * live-match setup) — instead it calls {@link MatchContextResolver} directly, the exact shared
 * component both paths delegate to, which is what actually needs the as-of-date bound. Per the
 * task's fallback: recording a lopsided result for a fixture (and its same-round peers) must NOT
 * change the league position computed for that fixture.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MatchContextResolverAsOfDateIT {

    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchContextResolver matchContextResolver;

    private SaveGame createSave() {
        User user = User.create("ctxasof_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        return saveGameRepository.findById(response.id()).orElseThrow();
    }

    @Test
    void recordingTheFixtureAndItsSameRoundPeersDoesNotChangeItsOwnLeaguePosition() {
        // Given a freshly created career: nothing has been played yet, so round 0's fixtures are
        // all unplayed and share the same matchDate.
        SaveGame save = createSave();
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).stream()
                .findFirst().orElseThrow();
        List<Fixture> round0 = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(save.getId(), competition.getId(), 0);
        assertThat(round0).hasSizeGreaterThanOrEqualTo(2);
        LocalDate matchDate = round0.get(0).getMatchDate();
        assertThat(round0).allMatch(f -> matchDate.equals(f.getMatchDate()),
                "every round-0 fixture shares the round's matchDate");

        // Pick as the target the round-0 fixture whose home club currently sits WORST in the table
        // (with nothing played, every club is tied on 0 points and ordered by name — so this is
        // simply not already #1), so a subsequent lopsided win is guaranteed to move it.
        MatchContextResolver.RoundContext baselineContext =
                matchContextResolver.forRound(save, competition.getId(), matchDate);
        Fixture target = round0.stream()
                .max(Comparator.comparingInt(f -> baselineContext.leaguePosition(f.getHomeClubId())))
                .orElseThrow();
        UUID homeClubId = target.getHomeClubId();
        int baselinePosition = baselineContext.leaguePosition(homeClubId);
        assertThat(baselinePosition).isGreaterThan(1);

        // When the target fixture itself, AND every other fixture sharing its round/matchDate,
        // record a lopsided home win — mirroring what FixtureSimulator/LiveMatchService do
        // immediately before resolving attendance context (fixture.recordResult(...) then a
        // MatchContextResolver call in the same flow).
        target.recordResult(9, 0);
        fixtureRepository.save(target);
        for (Fixture peer : round0) {
            if (peer.getId().equals(target.getId())) {
                continue;
            }
            peer.recordResult(5, 0);
            fixtureRepository.save(peer);
        }

        int afterRecordingPosition = matchContextResolver.forRound(save, competition.getId(), matchDate)
                .leaguePosition(homeClubId);

        // Then: the target's own league position, read as-of its own matchDate, is unaffected by
        // results dated on that same matchDate — its own result included. Pre-fix (no date bound),
        // the target's 9-0 win is the best goal difference among all the round's winners, so it
        // would climb straight to position 1, failing this assertion.
        assertThat(afterRecordingPosition)
                .as("league position must be as-of matchDate %s, unaffected by same-date results", matchDate)
                .isEqualTo(baselinePosition);
    }

    /**
     * Regression coverage for the Minor review finding: on matchday 1, nothing has been played
     * yet, so every club in the standings table is level on 0 points and
     * {@code StandingsCalculator}'s final tie-break (club name) alone decides who is "1st" and who
     * is "last". {@code AttendanceCalculator} prices that position (rank 1 → x1.10, rank 20 →
     * x0.90) as if it reflected real form, so — pre-fix — a club whose name sorts first draws a
     * materially bigger round-1 crowd than one whose name sorts last, for no footballing reason.
     * {@code leaguePosition} must detect "no fixture played yet" and return the same neutral
     * position for every club instead.
     */
    @Test
    void matchdayOneGivesEveryClubTheSameNeutralLeaguePosition() {
        // Given a freshly created career: round 0 is matchday 1 for every club in the competition,
        // so nothing has been played and every club is tied.
        SaveGame save = createSave();
        Competition competition = competitionRepository.findBySaveGameId(save.getId()).stream()
                .findFirst().orElseThrow();
        List<Fixture> round0 = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(save.getId(), competition.getId(), 0);
        assertThat(round0).hasSizeGreaterThanOrEqualTo(2);
        LocalDate matchDate = round0.get(0).getMatchDate();

        MatchContextResolver.RoundContext context =
                matchContextResolver.forRound(save, competition.getId(), matchDate);

        // When: reading the league position of every club playing in round 0, before anything has
        // been recorded.
        Set<Integer> positions = round0.stream()
                .flatMap(f -> Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .map(context::leaguePosition)
                .collect(Collectors.toSet());

        // Then: every club shares the exact same position — none is favoured or disfavoured by
        // where its name happens to fall alphabetically. Pre-fix, this table has as many distinct
        // positions as clubs (1..clubCount), failing hasSize(1) outright.
        assertThat(positions)
                .as("every club must share one neutral league position before any match is played")
                .hasSize(1);
    }
}
