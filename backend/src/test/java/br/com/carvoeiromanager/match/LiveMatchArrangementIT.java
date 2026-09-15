package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchArrangementIT {

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
        // "live_arrangement_" + a UUID exceeds users.username's varchar(50); "live_arrange_" fits
        // the same 50-char budget "live_tactics_" does in the sibling IT.
        User user = User.create("live_arrange_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        saveGameId = save.id();
        managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId, null, null);
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

    private static Map<String, Integer> slotsOf(LiveMatchState s) {
        return s.onPitch().stream()
                .collect(Collectors.toMap(LivePlayerResponse::playerId, LivePlayerResponse::slotIndex));
    }

    private static LiveTacticsRequest currentTacticsWith(LiveMatchState s, ArrangementRequest arrangement) {
        LiveTactics t = s.currentTactics();
        return new LiveTacticsRequest(t.mentality(), t.gameStyle(), t.attackZone(), t.pressing(), t.marking(),
                t.formation(), arrangement);
    }

    private LiveMatchState advanceToMinuteOne() {
        LiveMatchState state = liveMatchService.advance(saveGameId, userId);
        for (int i = 0; i < 90 && state.minute() < 1; i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        return state;
    }

    private static void assertInvalidLineup(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.INVALID_LINEUP));
    }

    @Test
    void kickoffReportsADistinctSlotIndexForEveryPlayerOnThePitch() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);

        assertThat(state.onPitch()).hasSize(11)
                .allSatisfy(p -> {
                    assertThat(p.slotIndex()).isBetween(0, 10);
                    assertThat(p.detailedPosition()).isNotNull();
                });
        assertThat(new HashSet<>(slotsOf(state).values())).hasSize(11);
        assertThat(state.bench()).allSatisfy(p -> assertThat(p.slotIndex()).isNull());
    }

    @Test
    void aSwapAppliesFromTheNextMinuteWithoutASpuriousTacticsChange() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        String a = state.onPitch().get(1).playerId();
        String b = state.onPitch().get(2).playerId();
        int aIndex = slots.get(a);
        int bIndex = slots.get(b);
        slots.put(a, bIndex);
        slots.put(b, aIndex);

        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).containsEntry(a, bIndex).containsEntry(b, aIndex);
        assertThat(after.events())
                .noneMatch(e -> "TACTICS_CHANGE".equals(e.type()) && e.home() == after.managedIsHome());
    }

    @Test
    void aSubstituteInheritsTheOutgoingPlayersSlot() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        String out = state.onPitch().get(5).playerId();
        int outIndex = slotsOf(state).get(out);
        String in = state.bench().get(0).playerId();

        liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(
                List.of(new SubstituteRequest(UUID.fromString(out), UUID.fromString(in)))));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).doesNotContainKey(out).containsEntry(in, outIndex);
    }

    @Test
    void anArrangementMayPlaceAPlayerSubstitutedOnInTheSameApply() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        String out = state.onPitch().get(5).playerId();
        String keeper = state.onPitch().get(0).playerId();
        String in = state.bench().get(0).playerId();
        liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(
                List.of(new SubstituteRequest(UUID.fromString(out), UUID.fromString(in)))));

        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        int outIndex = slots.remove(out);
        int keeperIndex = slots.get(keeper);
        slots.put(in, keeperIndex);
        slots.put(keeper, outIndex);
        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).containsEntry(in, keeperIndex).containsEntry(keeper, outIndex);
    }

    @Test
    void anArrangementThatLeavesAPlayerOutIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        slots.remove(state.onPitch().get(3).playerId());

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of()))));
    }

    @Test
    void anArrangementThatPutsTwoPlayersInOneSlotIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = new HashMap<>(slotsOf(state));
        slots.put(state.onPitch().get(1).playerId(), slots.get(state.onPitch().get(2).playerId()));

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of()))));
    }

    @Test
    void aRoleThatIsInvalidForTheSlotIsRejected() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> slots = slotsOf(state);
        String outfielder = state.onPitch().stream()
                .filter(p -> !"GK".equals(p.position())).findFirst().orElseThrow().playerId();

        assertInvalidLineup(() -> liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(slots, Map.of(outfielder, "GOALKEEPER")))));
    }

    /**
     * Final-review ruling F1(ii): toState reports {@code onPitch}/{@code bench} at the COMMITTED
     * minute (unlike {@code currentTactics}, F1(i) does not move this), so a manager who applies a
     * substitution and then reopens the dialog still sees the pre-substitution roster — the
     * arrangement they build is keyed by the OUTGOING player's id, not the incoming one
     * parseArrangement would otherwise expect. The service must remap that stale key rather than
     * reject an otherwise-valid drag as INVALID_LINEUP.
     */
    @Test
    void anArrangementKeyedByThePreSubstitutionRosterIsAcceptedAndLandsOnTheIncomingPlayer() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        Map<String, Integer> staleSlots = new HashMap<>(slotsOf(state));   // pre-substitution roster

        String out = state.onPitch().get(5).playerId();
        int outIndex = staleSlots.get(out);
        String in = state.bench().get(0).playerId();
        liveMatchService.substitute(saveGameId, userId, new SubstituteBatchRequest(
                List.of(new SubstituteRequest(UUID.fromString(out), UUID.fromString(in)))));

        // Two OTHER players, swapped — the request stays keyed by the OUTGOING player's id, exactly
        // what a dialog reopened against the stale (pre-substitution) roster would still hold.
        String a = state.onPitch().get(1).playerId();
        String b = state.onPitch().get(2).playerId();
        int aIndex = staleSlots.get(a);
        int bIndex = staleSlots.get(b);
        staleSlots.put(a, bIndex);
        staleSlots.put(b, aIndex);

        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(state, new ArrangementRequest(staleSlots, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(slotsOf(after)).doesNotContainKey(out).containsEntry(in, outIndex)
                .containsEntry(a, bIndex).containsEntry(b, aIndex);
    }

    /**
     * Final-review ruling F1(i): toState now reports the managed side's currentTactics at
     * committed+1 (what the NEXT apply builds on), not merely the committed minute — so a manager
     * who applies a formation change and reopens the dialog sees the NEW formation already, and a
     * pure drag re-submits that same formation instead of the stale one, which would otherwise
     * register as a real tactics change (R8) and silently revert the pending formation change.
     */
    @Test
    void aPureDragAfterAFormationChangeDoesNotRevertTheFormation() {
        LiveMatchState state = liveMatchService.kickoff(saveGameId, userId);
        LiveTactics t = state.currentTactics();
        LiveMatchState afterFormationChange = liveMatchService.changeTactics(saveGameId, userId,
                new LiveTacticsRequest(t.mentality(), t.gameStyle(), t.attackZone(), t.pressing(), t.marking(),
                        "4-3-3", null));
        assertThat(afterFormationChange.currentTactics().formation()).isEqualTo("4-3-3");

        // R10: the arrangement snapshot is still at the OLD formation (the change hasn't been
        // committed yet), so onPitch().slotIndex() is null for everyone here — assign distinct
        // indexes ourselves, pinning the goalkeeper to slot 0.
        List<LivePlayerResponse> onPitch = afterFormationChange.onPitch();
        Map<String, Integer> arrangement = new HashMap<>();
        int next = 1;
        for (LivePlayerResponse p : onPitch) {
            if ("GK".equals(p.position())) {
                arrangement.put(p.playerId(), 0);
            } else {
                arrangement.put(p.playerId(), next++);
            }
        }

        liveMatchService.changeTactics(saveGameId, userId,
                currentTacticsWith(afterFormationChange, new ArrangementRequest(arrangement, Map.of())));
        LiveMatchState after = advanceToMinuteOne();

        assertThat(after.currentTactics().formation()).isEqualTo("4-3-3");
    }
}
