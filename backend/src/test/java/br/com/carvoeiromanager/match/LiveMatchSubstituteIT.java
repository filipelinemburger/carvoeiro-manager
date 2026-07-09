package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
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
class LiveMatchSubstituteIT {

    @Autowired
    LiveMatchService liveMatchService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    LineupService lineupService;

    @Autowired
    br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private UUID saveGameId;
    private UUID userId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("live_sub_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        // Save a valid 11-player XI + bench players for the managed club
        List<Player> bestXi = lineupService.bestXi(managedClubId);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .collect(Collectors.toList());

        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);
    }

    @Test
    void substituteBringsOnABenchPlayerAndDecrementsRemaining() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        liveMatchService.advance(saveGameId, userId);   // reveal at least the first event

        UUID out = UUID.fromString(start.onPitch().get(6).playerId());
        UUID in = UUID.fromString(start.bench().get(0).playerId());
        LiveMatchState after = liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(new SubstituteRequest(out, in))));

        assertThat(after.subsRemaining()).isEqualTo(4);
        // The incoming player is now on the pitch once the clock reaches the sub minute.
        LiveMatchState later = after;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(later.status()); i++) later = liveMatchService.advance(saveGameId, userId);
        assertThat(later.events()).anyMatch(e -> "SUBSTITUTION".equals(e.type()) && in.toString().equals(e.playerInId()));
    }

    @Test
    void substituteRejectsAPlayerNotOnTheBench() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        UUID out = UUID.fromString(start.onPitch().get(0).playerId());
        UUID alsoOnPitch = UUID.fromString(start.onPitch().get(1).playerId());
        assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(new SubstituteRequest(out, alsoOnPitch)))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aBatchAppliesAllSubsAtTheSameMinuteInOrder() {
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);
        liveMatchService.advance(saveGameId, userId);   // reveal at least the first event

        UUID out1 = UUID.fromString(start.onPitch().get(6).playerId());
        UUID in1 = UUID.fromString(start.bench().get(0).playerId());
        UUID out2 = UUID.fromString(start.onPitch().get(7).playerId());
        UUID in2 = UUID.fromString(start.bench().get(1).playerId());

        // two valid pairs in one call: subsRemaining drops by 2, both SUBSTITUTION events
        // appear at the same minute, in the order submitted.
        LiveMatchState after = liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(
                        new SubstituteRequest(out1, in1),
                        new SubstituteRequest(out2, in2))));
        assertThat(after.subsRemaining()).isEqualTo(3);

        LiveMatchState later = after;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(later.status()); i++) later = liveMatchService.advance(saveGameId, userId);
        List<LiveEventResponse> subEvents = later.events().stream()
                .filter(e -> "SUBSTITUTION".equals(e.type())
                        && (in1.toString().equals(e.playerInId()) || in2.toString().equals(e.playerInId())))
                .toList();
        assertThat(subEvents).hasSize(2);
        assertThat(subEvents.get(0).minute()).isEqualTo(subEvents.get(1).minute());
        assertThat(subEvents.get(0).playerInId()).isEqualTo(in1.toString());
        assertThat(subEvents.get(1).playerInId()).isEqualTo(in2.toString());
    }

    @Test
    void aChainBatchIsValid() {
        // A-off/B-on then B-off/C-on in ONE batch: valid because validation evolves the
        // pitch state pair-by-pair. Final state: A and B off, C on.
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);

        UUID a = UUID.fromString(start.onPitch().get(6).playerId());
        UUID b = UUID.fromString(start.bench().get(0).playerId());
        UUID c = UUID.fromString(start.bench().get(1).playerId());

        LiveMatchState after = liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(
                        new SubstituteRequest(a, b),
                        new SubstituteRequest(b, c))));
        assertThat(after.subsRemaining()).isEqualTo(3);

        // Both subs are scheduled at minute 1; advance the clock so they are revealed.
        LiveMatchState later = after;
        for (int i = 0; i < 200 && !"FULL_TIME".equals(later.status()); i++) {
            later = liveMatchService.advance(saveGameId, userId);
            if (later.onPitch().stream().anyMatch(p -> c.toString().equals(p.playerId()))) break;
        }
        assertThat(later.onPitch()).noneMatch(p -> a.toString().equals(p.playerId()));
        assertThat(later.onPitch()).noneMatch(p -> b.toString().equals(p.playerId()));
        assertThat(later.onPitch()).anyMatch(p -> c.toString().equals(p.playerId()));
    }

    @Test
    void anInvalidPairRejectsTheWholeBatchAtomically() {
        // [valid pair, pair whose incoming player is already on the pitch]:
        // throws INVALID_SUBSTITUTION AND the first pair was NOT applied —
        // re-read state: subsRemaining still 5, no SUBSTITUTION events.
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);

        UUID out1 = UUID.fromString(start.onPitch().get(6).playerId());
        UUID in1 = UUID.fromString(start.bench().get(0).playerId());
        UUID out2 = UUID.fromString(start.onPitch().get(0).playerId());
        UUID alreadyOnPitch = UUID.fromString(start.onPitch().get(1).playerId());

        assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(
                        new SubstituteRequest(out1, in1),
                        new SubstituteRequest(out2, alreadyOnPitch)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_SUBSTITUTION));

        LiveMatchState reread = liveMatchService.getLive(saveGameId, userId).orElseThrow();
        assertThat(reread.subsRemaining()).isEqualTo(5);
        assertThat(reread.events()).noneMatch(e -> "SUBSTITUTION".equals(e.type()));
    }

    @Test
    void theSameIncomingPlayerTwiceIsRejected() {
        // [out1 -> inX, out2 -> inX]: INVALID_SUBSTITUTION (inX used by the earlier pair).
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);

        UUID out1 = UUID.fromString(start.onPitch().get(6).playerId());
        UUID out2 = UUID.fromString(start.onPitch().get(7).playerId());
        UUID inX = UUID.fromString(start.bench().get(0).playerId());

        assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId,
                new SubstituteBatchRequest(List.of(
                        new SubstituteRequest(out1, inX),
                        new SubstituteRequest(out2, inX)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.INVALID_SUBSTITUTION));

        LiveMatchState reread = liveMatchService.getLive(saveGameId, userId).orElseThrow();
        assertThat(reread.subsRemaining()).isEqualTo(5);
        assertThat(reread.events()).noneMatch(e -> "SUBSTITUTION".equals(e.type()));
    }

    @Test
    void aBatchBeyondRemainingSubsIsRejected() {
        // batch of MAX_SUBS + 1 pairs (duplicate pairs are fine — the size check fires
        // before pair validation): NO_SUBS_REMAINING.
        LiveMatchState start = liveMatchService.kickoff(saveGameId, userId);

        UUID out = UUID.fromString(start.onPitch().get(6).playerId());
        UUID in = UUID.fromString(start.bench().get(0).playerId());
        List<SubstituteRequest> pairs = java.util.stream.Stream
                .generate(() -> new SubstituteRequest(out, in))
                .limit(LiveMatchService.MAX_SUBS + 1)
                .toList();

        assertThatThrownBy(() -> liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(pairs)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NO_SUBS_REMAINING));

        LiveMatchState reread = liveMatchService.getLive(saveGameId, userId).orElseThrow();
        assertThat(reread.subsRemaining()).isEqualTo(5);
        assertThat(reread.events()).noneMatch(e -> "SUBSTITUTION".equals(e.type()));
    }
}
