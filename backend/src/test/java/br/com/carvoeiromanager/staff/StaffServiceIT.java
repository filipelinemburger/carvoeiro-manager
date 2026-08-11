package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.CreateSaveGameRequest;
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

import java.time.LocalDate;
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
    @Autowired SaveGameRepository saveGameRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("staff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** A career whose manager has a name of your choosing, rather than createCareer's default. */
    private Career createCareerManagedBy(String managerName) {
        User user = User.create("staff_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(
                user.getId(), new CreateSaveGameRequest(managerName, "Brasil")));
    }

    /**
     * Browses {@code role} day by day, advancing the save's own date, until the offer contains
     * someone employed at a rival — and leaves the save parked on that date so a follow-up
     * {@code hire} sees the same three candidates.
     *
     * <p>Free professionals and rivals' employees are drawn from one market, so whether any given
     * day offers a poachable candidate is a draw, not a certainty: PREPARADOR_FISICO has 8 free
     * against 20 employed elsewhere, which leaves about a 1-in-60 chance that all three slots come
     * back free. Pinning a single date would be that coin flip. Walking the calendar instead is
     * deterministic — the candidate seed folds in the date, so 60 consecutive misses is not a
     * number that happens.
     */
    private List<StaffCandidateResponse> browseUntilARivalsEmployeeIsOffered(Career c, StaffRole role) {
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        LocalDate browsingFrom = save.getCurrentDate();
        for (int day = 0; day < 60; day++) {
            save.setCurrentDate(browsingFrom.plusDays(day));
            saveGameRepository.saveAndFlush(save);
            List<StaffCandidateResponse> candidates =
                    staffService.getCandidates(c.id(), c.clubId(), role, c.userId());
            if (candidates.stream().anyMatch(x -> x.currentClubName() != null)) {
                return candidates;
            }
        }
        throw new AssertionError(
                "No " + role + " employed at a rival club was offered in 60 days of browsing.");
    }

    /**
     * Walks the save's date forward until the shortlist slot {@code index} is held by SOMEONE ELSE
     * — specifically a different rival's employee — and leaves the save parked on that date.
     *
     * <p>This is the situation the manager falls into just by playing: the offer seed folds in the
     * current date, so advancing a round reshuffles the whole market behind a list that is still on
     * screen. Landing the successor on an employed rival is what makes the stale click destructive
     * rather than merely wrong — that is the row a name-blind hire would delete.
     */
    private Staff advanceUntilThatSlotHoldsADifferentRivalsEmployee(Career c, StaffRole role,
                                                                    int index, String clickedName) {
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        LocalDate browsingFrom = save.getCurrentDate();
        for (int day = 1; day <= 60; day++) {
            save.setCurrentDate(browsingFrom.plusDays(day));
            saveGameRepository.saveAndFlush(save);
            StaffCandidateResponse nowOffered =
                    staffService.getCandidates(c.id(), c.clubId(), role, c.userId()).get(index);
            if (nowOffered.currentClubName() == null || nowOffered.name().equals(clickedName)) {
                continue;
            }
            return employedElsewhere(c, role, nowOffered.name());
        }
        throw new AssertionError("Slot " + index + " never came to hold a different rival's " + role
                + " in 60 days of advancing.");
    }

    /** The name currently on offer at {@code index} — what a client that just refreshed would send. */
    private String nameOffered(Career c, StaffRole role, int index) {
        return staffService.getCandidates(c.id(), c.clubId(), role, c.userId()).get(index).name();
    }

    /** The persisted row behind a candidate offered as someone else's employee. */
    private Staff employedElsewhere(Career c, StaffRole role, String name) {
        return staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == role)
                .filter(s -> !s.getClubId().equals(c.clubId()))
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        name + " is offered as employed but is on no rival's books"));
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
                c.id(), c.clubId(), StaffRole.OLHEIRO, 1, candidates.get(1).name(), c.userId());

        assertThat(roster.olheiro()).isNotNull();
        assertThat(roster.olheiro().name()).isEqualTo(candidates.get(1).name());
        assertThat(roster.olheiro().quality()).isEqualTo(candidates.get(1).quality());
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO)).isPresent();
    }

    @Test
    void hiringANewCandidateForAnAlreadyFilledRoleReplacesTheHolder() {
        Career c = createCareer();
        staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 0, nameOffered(c, StaffRole.ASSISTENTE, 0),
                c.userId());

        // Re-read the shortlist rather than reusing the first one: the hire above took a name out
        // of the market, which reshuffles what every index points at.
        StaffRosterResponse roster = staffService.hire(c.id(), c.clubId(), StaffRole.ASSISTENTE, 2,
                nameOffered(c, StaffRole.ASSISTENTE, 2), c.userId());

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

        // A name really on the shortlist, so the rejection can only be about the index.
        String realName = nameOffered(c, StaffRole.FISIOTERAPEUTA, 0);

        assertThatThrownBy(() -> staffService.hire(
                c.id(), c.clubId(), StaffRole.FISIOTERAPEUTA, 3, realName, c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    @Test
    void theManagedClubsHeadCoachSlotIsTheManagerThemselves() {
        // A DISTINCTIVE manager name on purpose. createCareer's default is literally "Técnico",
        // which is also a plausible hardcoded role label — asserting that string would pass even
        // if toRosterResponse ignored the manager persona and emitted a constant.
        Career c = createCareerManagedBy("Zé Ramalho");

        StaffRosterResponse roster = staffService.getRoster(c.id(), c.clubId(), c.userId());

        assertThat(roster.tecnico()).isNotNull();
        assertThat(roster.tecnico().name()).isEqualTo("Zé Ramalho");
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
    void candidatesIncludeStaffEmployedAtRivalClubs() {
        // Given a fresh career, whose rivals are staffed from club-staff.json
        Career c = createCareer();

        // When the manager browses for a fitness coach
        List<StaffCandidateResponse> candidates =
                browseUntilARivalsEmployeeIsOffered(c, StaffRole.PREPARADOR_FISICO);

        // Then someone on a rival's books is on the shortlist, named with the club they work for
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anySatisfy(x -> {
            assertThat(x.currentClubName()).isNotNull();
            // Not just any non-null string: the card must name the club that really employs them.
            Staff onTheBooks = staffRepository.findBySaveGameId(c.id()).stream()
                    .filter(s -> s.getRole() == StaffRole.PREPARADOR_FISICO)
                    .filter(s -> s.getName().equals(x.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            x.name() + " is offered as employed but is on no club's books"));
            assertThat(onTheBooks.getClubId()).isNotEqualTo(c.clubId());
            assertThat(clubRepository.findById(onTheBooks.getClubId()).orElseThrow().getName())
                    .isEqualTo(x.currentClubName());
        });
    }

    @Test
    void poachingEmptiesTheSourceClubsSlot() {
        // Given a shortlist containing a fitness coach employed by a rival
        Career c = createCareer();
        List<StaffCandidateResponse> candidates =
                browseUntilARivalsEmployeeIsOffered(c, StaffRole.PREPARADOR_FISICO);
        StaffCandidateResponse poached = candidates.stream()
                .filter(x -> x.currentClubName() != null)
                .findFirst()
                .orElseThrow();
        UUID sourceClubId = staffRepository.findBySaveGameId(c.id()).stream()
                .filter(s -> s.getRole() == StaffRole.PREPARADOR_FISICO)
                .filter(s -> s.getName().equals(poached.name()))
                .map(Staff::getClubId)
                .findFirst()
                .orElseThrow();

        // When the manager hires them
        staffService.hire(c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO, poached.index(),
                poached.name(), c.userId());

        // Then the rival really loses them — against Postgres, where the staff_club_id_role_key
        // unique constraint and the delete/insert flush order in hire() both apply for real. The
        // mock-level test can only prove the calls were made in that order.
        assertThat(staffRepository.findByClubIdAndRole(sourceClubId, StaffRole.PREPARADOR_FISICO))
                .isEmpty();
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.PREPARADOR_FISICO))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo(poached.name()));
    }

    @Test
    void aNameAlreadyHiredAtTheClubIsNeverOfferedAgain() {
        Career c = createCareer();
        List<StaffCandidateResponse> before = staffService.getCandidates(
                c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId());
        String hiredName = before.get(0).name();

        staffService.hire(c.id(), c.clubId(), StaffRole.OLHEIRO, 0, hiredName, c.userId());

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
                c.id(), c.clubId(), StaffRole.TECNICO, 0, "Qualquer Um", c.userId()))
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
                c.id(), rivalClubId, StaffRole.OLHEIRO, 0, "Qualquer Um", c.userId()))
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
    void hiringOnALaterDateWithAnIndexFromAnEarlierShortlistIsRejected() {
        // Given the manager reads the scout shortlist on day D and settles on the second name
        Career c = createCareer();
        StaffCandidateResponse clicked = staffService
                .getCandidates(c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId()).get(1);
        String olheiroBefore = staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO)
                .map(Staff::getName).orElse(null);

        // When the save's date moves on before they click — advancing a round is the commonest
        // action in the game — and slot 1 has come to hold a different rival's employee
        advanceUntilThatSlotHoldsADifferentRivalsEmployee(c, StaffRole.OLHEIRO, 1, clicked.name());

        // Then the click is refused outright, because the index no longer means what the manager
        // was looking at. Without the name on the request this signed the stranger silently.
        assertThatThrownBy(() -> staffService.hire(
                c.id(), c.clubId(), StaffRole.OLHEIRO, clicked.index(), clicked.name(), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.STALE_STAFF_CANDIDATES);
                    assertThat(api.getStatus().value()).isEqualTo(409);
                });
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO)
                .map(Staff::getName).orElse(null))
                .as("a refused hire must not put anybody on the club's payroll")
                .isEqualTo(olheiroBefore);
    }

    @Test
    void aRejectedStaleHireLeavesEveryRivalsStaffRowUntouched() {
        // Given the manager browsed a rival's fitness coach on one date
        Career c = createCareer();
        StaffCandidateResponse clicked =
                browseUntilARivalsEmployeeIsOffered(c, StaffRole.PREPARADOR_FISICO).stream()
                        .filter(x -> x.currentClubName() != null)
                        .findFirst()
                        .orElseThrow();
        Staff wanted = employedElsewhere(c, StaffRole.PREPARADOR_FISICO, clicked.name());

        // and the date then moved on, putting a DIFFERENT rival's employee in that same slot
        Staff bystander = advanceUntilThatSlotHoldsADifferentRivalsEmployee(
                c, StaffRole.PREPARADOR_FISICO, clicked.index(), clicked.name());
        Set<UUID> staffRowsBefore = staffRepository.findBySaveGameId(c.id()).stream()
                .map(Staff::getId)
                .collect(Collectors.toSet());

        // When the manager clicks the stale row, they are turned away
        assertThatThrownBy(() -> staffService.hire(c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO,
                clicked.index(), clicked.name(), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.STALE_STAFF_CANDIDATES));

        // Then nobody was robbed. This is the worst half of the bug: a name-blind hire poached
        // whoever the reshuffle had moved into that slot — a club losing its staff to a click the
        // manager aimed at somebody else entirely, with no confirmation and no undo.
        assertThat(staffRepository.findById(bystander.getId()))
                .as("the stranger the reshuffle moved into that slot must keep their job")
                .get()
                .satisfies(s -> assertThat(s.getClubId()).isEqualTo(bystander.getClubId()));
        assertThat(staffRepository.findByClubIdAndRole(wanted.getClubId(), StaffRole.PREPARADOR_FISICO))
                .as("the club the manager actually wanted to raid keeps their coach too")
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo(clicked.name()));
        assertThat(staffRepository.findBySaveGameId(c.id()).stream().map(Staff::getId))
                .as("a refused hire writes nothing at all")
                .containsExactlyInAnyOrderElementsOf(staffRowsBefore);
    }

    @Test
    void hiringTheNameTheCurrentShortlistShowsIsStillAccepted() {
        // The guard must reject stale clicks, not hiring itself — and refreshing has to be a real
        // way out of a rejection rather than a dead end.
        Career c = createCareer();
        staffService.getCandidates(c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId());
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(save.getCurrentDate().plusDays(1));
        saveGameRepository.saveAndFlush(save);

        StaffCandidateResponse refreshed = staffService
                .getCandidates(c.id(), c.clubId(), StaffRole.OLHEIRO, c.userId()).get(1);
        StaffRosterResponse roster = staffService.hire(c.id(), c.clubId(), StaffRole.OLHEIRO,
                refreshed.index(), refreshed.name(), c.userId());

        assertThat(roster.olheiro()).isNotNull();
        assertThat(roster.olheiro().name()).isEqualTo(refreshed.name());
        assertThat(staffRepository.findByClubIdAndRole(c.clubId(), StaffRole.OLHEIRO))
                .get()
                .satisfies(s -> assertThat(s.getName()).isEqualTo(refreshed.name()));
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
