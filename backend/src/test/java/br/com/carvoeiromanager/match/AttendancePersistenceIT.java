package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers Task 9: attendance is computed at match resolution (FixtureSimulator /
 * LiveMatchService, via AttendanceCalculator + MatchContextResolver) and persisted on the
 * fixture row, rather than being recomputed later by FinanceService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AttendancePersistenceIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LiveMatchService liveMatchService;
    @Autowired LineupService lineupService;
    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired PlayerRepository playerRepository;
    @Autowired SaveGameRepository saveGameRepository;

    private UUID userId;

    private UUID createSave() {
        User user = User.create("attendance_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return save.id();
    }

    /**
     * Advances until the next MATCH event is processed (skipping TRANSFER stops) — the same
     * pattern as {@code FinanceAdvanceIT#advanceToNextMatch}, reused here rather than inventing
     * a new advance path.
     */
    private void advanceOneRound(UUID saveGameId) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(saveGameId, userId);
            if ("MATCH".equals(r.type())) {
                return;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void everyPlayedFixtureRecordsAPositiveAttendance() {
        UUID saveGameId = createSave();
        advanceOneRound(saveGameId);

        List<Fixture> played = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .toList();

        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(fixture ->
                assertThat(fixture.getAttendance())
                        .as("attendance of fixture %s", fixture.getId())
                        .isNotNull()
                        .isPositive());
    }

    /**
     * Covers the binding constraint that the two attendance call sites — {@link LiveMatchService
     * #finish} (the managed club's live-played fixture) and {@link FixtureSimulator} (every other
     * fixture) — must compute identical attendance for identical inputs. A bug was previously
     * found and fixed here: league position was read from live standings that included the
     * match's own just-recorded result, so the two paths silently disagreed depending on call
     * order. {@link MatchContextResolver}'s as-of-{@code matchDate} bound now makes both call
     * sites share exactly the same lookup regardless of when each runs.
     *
     * <p>This drives BOTH real service paths on the literal SAME fixture: first
     * {@code LiveMatchService#finish} resolves it (live match played to full time), then
     * {@code FixtureSimulator#simulateRound} is invoked a second time on that identical fixture.
     * Every input {@code AttendanceCalculator} depends on — home/away club capacity and
     * reputation, as-of-matchDate recent form and league position, weather — is unaffected by
     * that second call (form/standings are strictly-before-matchDate, so this round's own results
     * never count towards themselves; weather is derived deterministically from the fixture id
     * and match date). So if the two call sites ever compute their shared inputs differently
     * again, this fixture's attendance changes between the two calls and the test fails.
     */
    @Test
    void liveMatchFinishAndFixtureSimulatorAgreeOnAttendanceForTheSameFixture() {
        User user = User.create("attn_cross_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        UUID uId = user.getId();
        SaveGameResponse save = saveGameService.createCareer(uId);
        UUID saveGameId = save.id();
        UUID managedClubId = save.managedClubId();

        // Save a valid 11-player XI + 3 bench players for the managed club (same pattern as
        // LiveMatchFinishIT) so kickoff/finish can run to completion.
        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), uId);

        LiveMatchState start = liveMatchService.kickoff(saveGameId, uId);
        UUID fixtureId = UUID.fromString(start.fixtureId());

        LiveMatchState s = start;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(s.status()); i++) {
            s = liveMatchService.advance(saveGameId, uId);
        }

        liveMatchService.finish(saveGameId, uId);

        Fixture liveResolved = fixtureRepository.findById(fixtureId).orElseThrow();
        Integer attendanceFromLiveMatchFinish = liveResolved.getAttendance();
        assertThat(attendanceFromLiveMatchFinish).as("attendance set by LiveMatchService#finish")
                .isNotNull();

        // Re-resolve the EXACT SAME fixture through FixtureSimulator's independent code path.
        SaveGame reloadedSave = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(reloadedSave, liveResolved.getCompetitionId(), List.of(liveResolved));

        Integer attendanceFromFixtureSimulator =
                fixtureRepository.findById(fixtureId).orElseThrow().getAttendance();

        assertThat(attendanceFromFixtureSimulator)
                .as("FixtureSimulator's attendance for the identical fixture/state")
                .isEqualTo(attendanceFromLiveMatchFinish);
    }

    @Test
    void unplayedFixturesHaveNoAttendance() {
        UUID saveGameId = createSave();

        List<Fixture> unplayed = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(f -> !f.isPlayed())
                .toList();

        assertThat(unplayed).isNotEmpty();
        assertThat(unplayed).allMatch(f -> f.getAttendance() == null);
    }
}
