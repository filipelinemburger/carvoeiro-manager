package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

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
