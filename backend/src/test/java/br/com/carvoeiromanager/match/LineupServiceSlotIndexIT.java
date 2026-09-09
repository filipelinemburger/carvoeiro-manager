package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.assertj.core.api.ThrowableAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ITs for the explicit slot assignment on setLineup/getLineup: all-or-nothing slotIndexes,
 * permutation of 0..10, bench never carries one, and the save→get roundtrip echoes what was sent.
 *
 * <p>Slot 0 is NOT reserved for a goalkeeper (spec §5.1: the manager may improvise anyone
 * anywhere, including in goal) — only the role played there has to suit the slot.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LineupServiceSlotIndexIT {

    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;

    private record Career(UUID userId, UUID saveGameId, UUID clubId) {}

    /** The one rejection contract every invalid-slot request shares: ApiException + INVALID_LINEUP. */
    private void assertRejected(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        apiEx -> assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVALID_LINEUP));
    }

    private Career createCareer() {
        User user = User.create("slotidx_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save.id(), save.managedClubId());
    }

    /**
     * A valid XI where the GK takes slot 0 and the outfielders take 1..10 in bestXi order. Players
     * may be "out of position" for their index — legal by design — so the role is derived from the
     * SLOT, which is what the service validates against once an explicit slot is given.
     */
    private List<LineupStarter> startersWithSlots(UUID clubId) {
        List<Player> xi = lineupService.bestXi(clubId, null, null);
        List<LineupStarter> starters = new ArrayList<>();
        int next = 1;
        for (Player p : xi) {
            int slot = p.getPosition() == Position.GK ? 0 : next++;
            starters.add(new LineupStarter(p.getId(), roleForSlot("4-4-2", slot), null, null, slot));
        }
        return starters;
    }

    /** The default archetype of the (single) detailed position a formation slot accepts. */
    private static String roleForSlot(String formation, int slot) {
        return RoleArchetype.defaultFor(
                FormationSlots.of(formation).get(slot).accepted().iterator().next()).name();
    }

    private List<LineupStarter> startersWithoutSlots(UUID clubId) {
        return lineupService.bestXi(clubId, null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
    }

    @Test
    void savesSlotIndexesAndGetLineupEchoesThemBack() {
        Career career = createCareer();
        List<LineupStarter> starters = startersWithSlots(career.clubId());

        LineupResponse saved = lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId());
        assertThat(saved.starters()).extracting(LineupStarter::slotIndex)
                .containsExactlyInAnyOrderElementsOf(
                        starters.stream().map(LineupStarter::slotIndex).toList());

        LineupResponse loaded = lineupService.getLineup(career.saveGameId(), career.clubId(),
                career.userId()).orElseThrow();
        for (LineupStarter sent : starters) {
            LineupStarter got = loaded.starters().stream()
                    .filter(s -> s.playerId().equals(sent.playerId())).findFirst().orElseThrow();
            assertThat(got.slotIndex()).isEqualTo(sent.slotIndex());
        }
    }

    @Test
    void rejectsMixedNullAndNonNullSlotIndexes() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        LineupStarter last = starters.remove(starters.size() - 1);
        starters.add(new LineupStarter(last.playerId(), last.role(), null, null, null));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsDuplicateSlotIndexes() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        // Break the permutation: give the last outfielder the same index as the previous one.
        LineupStarter a = starters.get(9);
        LineupStarter b = starters.remove(10);
        starters.add(new LineupStarter(b.playerId(), b.role(), null, null, a.slotIndex()));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void rejectsOutOfRangeSlotIndex() {
        Career career = createCareer();
        List<LineupStarter> starters = new ArrayList<>(startersWithSlots(career.clubId()));
        LineupStarter last = starters.remove(starters.size() - 1);
        starters.add(new LineupStarter(last.playerId(), last.role(), null, null, 11));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId()));
    }

    @Test
    void acceptsANonGoalkeeperAtSlotZero() {
        // Spec §5.1: the manager may improvise anyone anywhere, including in goal. Swapping slot 0
        // (the keeper) with slot 1 (an outfielder) is legal as long as each plays a role that suits
        // the slot they now occupy.
        Career career = createCareer();
        List<LineupStarter> starters = startersWithSlots(career.clubId()).stream()
                .map(s -> s.slotIndex() == 0
                        ? new LineupStarter(s.playerId(), roleForSlot("4-4-2", 1), null, null, 1)
                        : s.slotIndex() == 1
                        ? new LineupStarter(s.playerId(), roleForSlot("4-4-2", 0), null, null, 0)
                        : s)
                .collect(Collectors.toList());

        LineupResponse saved = lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, null), career.userId());

        assertThat(saved.starters()).hasSize(11);
        LineupResponse loaded = lineupService.getLineup(career.saveGameId(), career.clubId(),
                career.userId()).orElseThrow();
        assertThat(loaded.starters()).extracting(LineupStarter::slotIndex)
                .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void rejectsBenchEntryWithSlotIndex() {
        Career career = createCareer();
        List<LineupStarter> starters = startersWithSlots(career.clubId());
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(career.clubId());
        Player benchPlayer = squad.stream()
                .filter(p -> starters.stream().noneMatch(s -> s.playerId().equals(p.getId())))
                .filter(Player::isEligible)
                .findFirst().orElseThrow();
        List<LineupStarter> bench = List.of(new LineupStarter(benchPlayer.getId(),
                RoleArchetype.defaultFor(benchPlayer.getDetailedPosition()).name(), null, null, 5));

        assertRejected(() -> lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", starters, bench), career.userId()));
    }

    @Test
    void legacyRequestWithoutSlotIndexesStillSavesAndReadsBackNulls() {
        Career career = createCareer();
        LineupResponse saved = lineupService.setLineup(career.saveGameId(), career.clubId(),
                new SetLineupRequest("4-4-2", startersWithoutSlots(career.clubId()), null),
                career.userId());
        assertThat(saved.starters()).hasSize(11);

        LineupResponse loaded = lineupService.getLineup(career.saveGameId(), career.clubId(),
                career.userId()).orElseThrow();
        assertThat(loaded.starters()).extracting(LineupStarter::slotIndex).containsOnlyNulls();
    }
}
