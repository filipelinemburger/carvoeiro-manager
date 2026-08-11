package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class StaffServiceIT {

    @Autowired StaffService staffService;
    @Autowired StaffRepository staffRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("staff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void freshCareerStartsWithTheResearchedCommission() {
        Career c = createCareer();

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        // A fresh career no longer hands the manager an empty building: Criciúma's research
        // found a named ASSISTENTE, PREPARADOR_FISICO and OLHEIRO, so getRoster's DTO mapping
        // must surface all three. The FISIOTERAPEUTA slot stays null on purpose — the research
        // found five physios at the club with no identifiable first-team lead, so an empty slot
        // here is real data, not a gap in the seeding or the mapping code.
        assertThat(roster.assistente()).isNotNull();
        assertThat(roster.preparadorFisico()).isNotNull();
        assertThat(roster.olheiro()).isNotNull();
        assertThat(roster.fisioterapeuta()).isNull();
    }

    @Test
    void candidatesForARoleAreThreeAndStableWithinTheSameCall() {
        Career c = createCareer();

        List<StaffCandidateResponse> first = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());
        List<StaffCandidateResponse> second = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());

        assertThat(first).hasSize(3);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void hiringACandidatePersistsThemInTheRoster() {
        Career c = createCareer();
        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId());

        StaffRosterResponse roster = staffService.hire(
                c.id(), c.clubId(), StaffRole.OLHEIRO, 1, c.userId());

        assertThat(roster.olheiro()).isNotNull();
        assertThat(roster.olheiro().name()).isEqualTo(candidates.get(1).name());
        assertThat(roster.olheiro().quality()).isEqualTo(candidates.get(1).quality());
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO)).isPresent();
    }

    @Test
    void hiringANewCandidateForAnAlreadyFilledRoleReplacesTheHolder() {
        Career c = createCareer();
        staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 0, c.userId());

        StaffRosterResponse roster = staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 2, c.userId());

        // The managed club now starts with a researched commission of its own (career creation
        // seeds it), so asserting a total roster size of one no longer holds — assert instead
        // that the ASSISTENTE role specifically was replaced, not duplicated.
        assertThat(staffRepository.findByClubId(c.clubId()).stream()
                .filter(s -> s.getRole() == StaffRole.ASSISTENTE)
                .toList()).hasSize(1);
        assertThat(roster.assistente()).isNotNull();
    }

    @Test
    void hiringAnOutOfRangeCandidateIndexIsRejected() {
        Career c = createCareer();

        assertThatThrownBy(() -> staffService.hire(c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA, 3, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    @Test
    void theManagedClubsHeadCoachSlotIsTheManagerThemselves() {
        Career c = createCareer();

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        assertThat(roster.tecnico()).isNotNull();
        assertThat(roster.tecnico().name()).isEqualTo("Técnico");   // createCareer's default manager name
        // Null quality/wage is how the frontend recognises "this is you, not a hire".
        assertThat(roster.tecnico().quality()).isNull();
        assertThat(roster.tecnico().wage()).isNull();
    }

    @Test
    void aRivalClubsHeadCoachSlotIsTheirRealCoach() {
        Career c = createCareer();
        UUID rivalClubId = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == StaffRole.TECNICO)
                .map(Staff::getClubId)
                .findFirst()
                .orElseThrow();

        StaffRosterResponse roster = staffService.getRoster(c.id(), rivalClubId, c.userId());

        assertThat(roster.tecnico()).isNotNull();
        assertThat(roster.tecnico().quality()).isNotNull();
        assertThat(roster.tecnico().wage()).isNotNull();
    }

    @Test
    void aRivalClubWithNoResearchedCoachLeavesTheSlotNull() {
        Career c = createCareer();
        // Not every rival club's research turned up a named head coach (two currently don't).
        // Pick one dynamically rather than hardcoding a short name: the seed research may be
        // refreshed later, and a club pinned by name today could gain a coach tomorrow, which
        // would fail this test for the wrong reason.
        Set<UUID> clubsWithTecnico = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == StaffRole.TECNICO)
                .map(Staff::getClubId)
                .collect(Collectors.toSet());
        UUID rivalClubWithoutCoach = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()))
                .filter(id -> !clubsWithTecnico.contains(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected at least one rival club with no seeded TECNICO — none found. "
                                + "The seed research may have changed since this test was written."));

        StaffRosterResponse roster = staffService.getRoster(c.id(), rivalClubWithoutCoach, c.userId());

        assertThat(roster.tecnico()).isNull();
    }

    @Test
    void aFreshCareerIsOfferedOnlyFreeProfessionalsWhileThePoolOutnumbersTheSlots() {
        Career c = createCareer();

        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, c.userId());

        // Candidates come in three strict tiers — free pool, then rivals' staff, then newgens —
        // and staff-pool.json still holds 8 free PREPARADOR_FISICO against 3 offered slots, so
        // tier 2 is never reached and no candidate here carries a club to be poached from. That
        // is a consequence of the tier order, not of the poaching code: StaffServiceTest and
        // StaffCandidateGeneratorTest cover what happens once tier 1 runs dry. If trimming the
        // pool (or reordering the tiers) ever makes poaching reachable at a fresh career, this
        // test is the canary that says so.
        assertThat(candidates).hasSize(3);
        assertThat(candidates).allSatisfy(x -> assertThat(x.currentClubName()).isNull());
    }

    @Test
    void aNameAlreadyHiredAtTheClubIsNeverOfferedAgain() {
        Career c = createCareer();
        List<StaffCandidateResponse> before = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId());
        String hiredName = before.get(0).name();

        staffService.hire(c.id(), c.clubId(), StaffRole.OLHEIRO, 0, c.userId());

        // The exclusion narrowed from save-wide to this club only, so it must still hold for the
        // club's own staff — otherwise the manager could be offered someone they already employ.
        assertThat(staffService.getCandidates(c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId()))
                .extracting(StaffCandidateResponse::name)
                .doesNotContain(hiredName);
    }

    @Test
    void theHeadCoachCanNeverBeBrowsedOrHired() {
        Career c = createCareer();

        assertThatThrownBy(() -> staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.TECNICO, c.userId()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> staffService.hire(
                c.id(), c.clubId(), StaffRole.TECNICO, 0, c.userId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void hiringForARivalClubInYourOwnSaveIsRejected() {
        Career c = createCareer();
        // A real rival resolved from the save rather than pinned by short name: clubId is a free
        // path variable on StaffController, so without requireManagedClub a crafted request would
        // staff a rival at the rival's expense — and, since hire reports managedClub=true, would
        // hand back the player's own manager name inside that rival's roster.
        UUID rivalClubId = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId)
                .filter(id -> !id.equals(c.clubId()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> staffService.hire(
                c.id(), rivalClubId, StaffRole.OLHEIRO, 0, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(api.getStatus().value()).isEqualTo(400);
                });
        assertThatThrownBy(() -> staffService.getCandidates(
                c.id(), rivalClubId, StaffRole.OLHEIRO, c.userId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aClubFromADifferentSaveGameIsRejected() {
        // Two independent save games, each with its own user (createCareer mints a fresh User every
        // call) — c2's club must not be reachable through c1's save-game id, proving the
        // requireClubInSave guard blocks cross-save-game access regardless of which user owns which save.
        Career c1 = createCareer();
        Career c2 = createCareer();

        assertThatThrownBy(() -> staffService.getRoster(c1.id(), c2.clubId(), c1.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.CLUB_NOT_FOUND);
                    assertThat(api.getStatus().value()).isEqualTo(404);
                });
    }
}
