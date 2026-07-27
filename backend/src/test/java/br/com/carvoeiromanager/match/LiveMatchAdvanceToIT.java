package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchAdvanceToIT {

    @Autowired
    LiveMatchService liveMatchService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupService lineupService;

    @Autowired
    LiveMatchRepository liveMatchRepository;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_advto_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

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

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    @Test
    void advanceToHalfTimeFromKickoffReachesHalfTimeAtMinute45() {
        liveMatchService.kickoff(saveGameId, userId);

        LiveMatchState state = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.HALF_TIME);

        assertThat(state.status()).isEqualTo("HALF_TIME");
        assertThat(state.minute()).isEqualTo(45);
    }

    @Test
    void advanceToFullTimeFromKickoffReachesFullTime() {
        liveMatchService.kickoff(saveGameId, userId);

        LiveMatchState state = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME);

        assertThat(state.status()).isEqualTo("FULL_TIME");
        assertThat(state.minute()).isEqualTo(90);
    }

    @Test
    void advanceToHalfTimeWhenAlreadyInTheSecondHalfIsIdempotent() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState s = null;
        // Advance manually well past half-time (but not to full time).
        for (int i = 0; i < 200 && (s == null || s.minute() < 60); i++) {
            s = liveMatchService.advance(saveGameId, userId);
            if ("FULL_TIME".equals(s.status())) break;
        }
        // Skip this test's assertions if the random match somehow finished before minute 60 —
        // extremely unlikely, but keep the invariant explicit rather than silently passing.
        assertThat(s.status()).isNotEqualTo("FULL_TIME");
        LiveMatchState before = s;

        LiveMatchState after = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.HALF_TIME);

        assertThat(after.minute()).isEqualTo(before.minute());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.homeGoals()).isEqualTo(before.homeGoals());
        assertThat(after.awayGoals()).isEqualTo(before.awayGoals());
        assertThat(after.events()).isEqualTo(before.events());
    }

    @Test
    void advanceToFullTimeWhenAlreadyFinishedIsIdempotent() {
        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState finished = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME);

        LiveMatchState again = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME);

        assertThat(again.minute()).isEqualTo(finished.minute());
        assertThat(again.status()).isEqualTo(finished.status());
        assertThat(again.homeGoals()).isEqualTo(finished.homeGoals());
        assertThat(again.awayGoals()).isEqualTo(finished.awayGoals());
        assertThat(again.events()).isEqualTo(finished.events());
    }

    @Test
    void advanceToWithNoLiveMatchIsRejectedLikeOtherLiveMatchEndpoints() {
        assertThatThrownBy(() -> liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME))
                .isInstanceOf(ApiException.class);
    }

    /**
     * Determinism: stepping minute-by-minute via {@link LiveMatchService#advance} all the way to
     * full time must consume the exact same RNG draws — in the same order — as one
     * {@link LiveMatchService#advanceTo} call for the same underlying seed/schedules. We prove
     * this by capturing the final state from a manual advance loop, then rewinding the SAME live
     * match session (same seed, same empty sub/tactics schedules) back to minute 0 and replaying
     * it with a single advanceTo(FULL_TIME) call — the final score and event list must match
     * exactly.
     */
    @Test
    void minuteByMinuteAdvanceAndOneShotAdvanceToProduceIdenticalFinalScoreAndEvents() {
        liveMatchService.kickoff(saveGameId, userId);

        LiveMatchState manual = null;
        for (int i = 0; i < 200 && (manual == null || !"FULL_TIME".equals(manual.status())); i++) {
            manual = liveMatchService.advance(saveGameId, userId);
        }
        assertThat(manual.status()).isEqualTo("FULL_TIME");

        // Rewind the same session back to kickoff — seed and (empty) schedules are untouched, so
        // the engine simulation replays byte-identically.
        LiveMatch lm = liveMatchRepository.findBySaveGameId(saveGameId).orElseThrow();
        lm.setCommittedMinute(0);
        liveMatchRepository.save(lm);

        LiveMatchState oneShot = liveMatchService.advanceTo(saveGameId, userId, AdvanceTarget.FULL_TIME);

        assertThat(oneShot.homeGoals()).isEqualTo(manual.homeGoals());
        assertThat(oneShot.awayGoals()).isEqualTo(manual.awayGoals());
        assertThat(oneShot.events()).isEqualTo(manual.events());
    }

    /**
     * advance-to must never stop early because it crossed a PAUSED_INJURY/PAUSED_RED minute for
     * the managed side — those are auto-continued through. Run several independent matches (each
     * with a different random seed derived from a fresh save game) so that, across trials, at
     * least some encounter such a pause; every single one must still land exactly on FULL_TIME at
     * minute 90 in one advanceTo call, proving no trial got stuck on a pause status.
     */
    @Test
    void advanceToFullTimeAlwaysReachesFullTimeAcrossManyRandomMatches() {
        for (int trial = 0; trial < 10; trial++) {
            User user = User.create("live_advto_p_" + UUID.randomUUID(), "hash");
            userRepository.save(user);
            UUID trialUserId = user.getId();
            SaveGameResponse save = saveGameService.createCareer(trialUserId);
            UUID trialSaveGameId = save.id();
            UUID trialClubId = save.managedClubId();

            List<Player> bestXi = lineupService.bestXi(trialClubId);
            List<LineupStarter> starters = bestXi.stream()
                    .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                    .collect(Collectors.toList());
            List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
            List<LineupStarter> bench = playerRepository.findByClubId(trialClubId).stream()
                    .filter(p -> !xiIds.contains(p.getId()))
                    .filter(Player::isEligible)
                    .limit(3)
                    .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                    .collect(Collectors.toList());
            lineupService.setLineup(trialSaveGameId, trialClubId, new SetLineupRequest("4-4-2", starters, bench), trialUserId);

            liveMatchService.kickoff(trialSaveGameId, trialUserId);
            LiveMatchState state = liveMatchService.advanceTo(trialSaveGameId, trialUserId, AdvanceTarget.FULL_TIME);

            assertThat(state.status()).isEqualTo("FULL_TIME");
            assertThat(state.minute()).isEqualTo(90);
        }
    }
}
