package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.season.MatchPreviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Covers the poaching wiring at the seam, with the generator stubbed. Which candidates a real
 * career is offered is a draw against the market, so stubbing is what lets these tests state
 * exactly what {@code hire} was handed; {@link StaffServiceIT} proves the same behaviour end to
 * end against Postgres, where it has to walk the calendar to find a poachable candidate.
 */
class StaffServiceTest {

    private final SaveGameService saveGameService = Mockito.mock(SaveGameService.class);
    private final StaffRepository staffRepository = Mockito.mock(StaffRepository.class);
    private final StaffVacancyRepository staffVacancyRepository =
            Mockito.mock(StaffVacancyRepository.class);
    private final StaffCandidateGenerator staffCandidateGenerator =
            Mockito.mock(StaffCandidateGenerator.class);
    private final ClubRepository clubRepository = Mockito.mock(ClubRepository.class);
    private final MatchPreviewService matchPreviewService = Mockito.mock(MatchPreviewService.class);
    private final StaffService staffService = new StaffService(saveGameService, staffRepository,
            staffVacancyRepository, staffCandidateGenerator, clubRepository, matchPreviewService);

    private final UUID saveGameId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Club managedClub = Club.create(saveGameId, "Criciúma Esporte Clube", "CRI");
    private final Club rivalClub = Club.create(saveGameId, "Avaí Futebol Clube", "AVA");

    private SaveGame stubSave() {
        SaveGame save = SaveGame.create(userId, 2026);
        save.setManagedClubId(managedClub.getId());
        save.setCurrentDate(LocalDate.of(2026, 1, 10));
        Mockito.when(saveGameService.requireOwned(saveGameId, userId)).thenReturn(save);
        Mockito.when(clubRepository.findById(managedClub.getId()))
                .thenReturn(java.util.Optional.of(managedClub));
        Mockito.when(clubRepository.findById(rivalClub.getId()))
                .thenReturn(java.util.Optional.of(rivalClub));
        Mockito.when(clubRepository.findBySaveGameId(saveGameId))
                .thenReturn(List.of(managedClub, rivalClub));
        return save;
    }

    private Staff rivalScout() {
        return Staff.create(saveGameId, rivalClub.getId(), StaffRole.OLHEIRO, StaffQuality.EXCELENTE,
                "Rival Contratado", new BigDecimal("80000.00"), 16, 15);
    }

    private void stubCandidates(StaffCandidate... candidates) {
        Mockito.when(staffCandidateGenerator.generate(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(List.of(candidates));
    }

    /** Puts {@code poached} on the rival's books AND makes them findable by id, as in the database. */
    private void stubEmployed(Staff poached) {
        Mockito.when(staffRepository.findBySaveGameId(saveGameId)).thenReturn(List.of(poached));
        Mockito.when(staffRepository.findById(poached.getId()))
                .thenReturn(java.util.Optional.of(poached));
    }

    @Test
    void hiringAPoachingTargetDeletesTheSourceClubsRow() {
        // Given a rival scout offered as a poaching target
        stubSave();
        Staff poached = rivalScout();
        stubEmployed(poached);
        stubCandidates(new StaffCandidate("Rival Contratado", StaffQuality.EXCELENTE,
                new BigDecimal("80000.00"), 16, 15, poached.getId(), "Avaí Futebol Clube"));

        // When the manager hires them
        staffService.hire(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, 0, "Rival Contratado", userId);

        // Then the rival loses the row, and the delete is flushed before the new row is queued —
        // Hibernate would otherwise run the pending insert first.
        InOrder order = Mockito.inOrder(staffRepository);
        order.verify(staffRepository).deleteById(poached.getId());
        order.verify(staffRepository).deleteByClubIdAndRole(managedClub.getId(), StaffRole.OLHEIRO);
        order.verify(staffRepository).flush();
        ArgumentCaptor<Staff> hired = ArgumentCaptor.forClass(Staff.class);
        order.verify(staffRepository).save(hired.capture());
        assertThat(hired.getValue().getName()).isEqualTo("Rival Contratado");
        assertThat(hired.getValue().getClubId()).isEqualTo(managedClub.getId());
    }

    @Test
    void poachingRecordsAVacancyForTheSourceClubAndRole() {
        // Given a rival scout offered as a poaching target
        stubSave();
        Staff poached = rivalScout();
        stubEmployed(poached);
        stubCandidates(new StaffCandidate("Rival Contratado", StaffQuality.EXCELENTE,
                new BigDecimal("80000.00"), 16, 15, poached.getId(), "Avaí Futebol Clube"));

        // When the manager hires them
        staffService.hire(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, 0, "Rival Contratado", userId);

        // Then the hole they leave is written down, against the club and role read off the
        // PERSISTED row — not off the candidate, which is an unpersisted roll.
        ArgumentCaptor<StaffVacancy> vacancy = ArgumentCaptor.forClass(StaffVacancy.class);
        Mockito.verify(staffVacancyRepository).save(vacancy.capture());
        assertThat(vacancy.getValue().getClubId()).isEqualTo(rivalClub.getId());
        assertThat(vacancy.getValue().getRole()).isEqualTo(StaffRole.OLHEIRO);
        assertThat(vacancy.getValue().getSaveGameId()).isEqualTo(saveGameId);
    }

    @Test
    void poachingASlotAlreadyMarkedVacantDoesNotWriteASecondRow() {
        stubSave();
        Staff poached = rivalScout();
        stubEmployed(poached);
        Mockito.when(staffVacancyRepository.existsByClubIdAndRole(rivalClub.getId(), StaffRole.OLHEIRO))
                .thenReturn(true);
        stubCandidates(new StaffCandidate("Rival Contratado", StaffQuality.EXCELENTE,
                new BigDecimal("80000.00"), 16, 15, poached.getId(), "Avaí Futebol Clube"));

        staffService.hire(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, 0, "Rival Contratado", userId);

        // staff_vacancy is UNIQUE on (club_id, role): a blind insert would be a constraint
        // violation the day AI clubs start refilling their slots.
        Mockito.verify(staffVacancyRepository, Mockito.never()).save(any());
        Mockito.verify(staffRepository).deleteById(poached.getId());
    }

    @Test
    void poachingSomeoneWhoseSourceRowHasVanishedIsRejected() {
        // Given a candidate pointing at a staff row that is no longer there
        stubSave();
        UUID goneStaffId = UUID.randomUUID();
        stubCandidates(new StaffCandidate("Fantasma da Silva", StaffQuality.EXCELENTE,
                new BigDecimal("80000.00"), 16, 15, goneStaffId, "Avaí Futebol Clube"));
        Mockito.when(staffRepository.findById(goneStaffId)).thenReturn(java.util.Optional.empty());

        // When the manager tries to hire them, they are turned away — deleteById would silently
        // no-op, and the manager would be handed a roster claiming a hire that never emptied anyone.
        assertThatThrownBy(() -> staffService.hire(
                saveGameId, managedClub.getId(), StaffRole.OLHEIRO, 0, "Fantasma da Silva", userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(api.getStatus().value()).isEqualTo(400);
                });
        Mockito.verify(staffRepository, Mockito.never()).save(any());
        Mockito.verify(staffVacancyRepository, Mockito.never()).save(any());
    }

    @Test
    void hiringAFreeProfessionalLeavesEveryOtherClubUntouched() {
        stubSave();
        Mockito.when(staffRepository.findBySaveGameId(saveGameId)).thenReturn(List.of(rivalScout()));
        stubCandidates(StaffCandidate.free("Livre da Silva", StaffQuality.BOM,
                new BigDecimal("45000.00"), 13, 12));

        staffService.hire(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, 0, "Livre da Silva", userId);

        Mockito.verify(staffRepository, Mockito.never()).deleteById(any());
        // No club lost anybody, so no club has a hole to record either.
        Mockito.verify(staffVacancyRepository, Mockito.never()).save(any());
    }

    @Test
    void poachingTargetsAreEveryOtherClubsStaffInTheSameRole() {
        stubSave();
        Staff poachable = rivalScout();
        Staff wrongRole = Staff.create(saveGameId, rivalClub.getId(), StaffRole.FISIOTERAPEUTA,
                StaffQuality.BOM, "Fisio do Rival", new BigDecimal("45000.00"), 13, 12);
        Staff ownStaff = Staff.create(saveGameId, managedClub.getId(), StaffRole.OLHEIRO,
                StaffQuality.BOM, "Olheiro da Casa", new BigDecimal("45000.00"), 13, 12);
        Mockito.when(staffRepository.findBySaveGameId(saveGameId))
                .thenReturn(List.of(poachable, wrongRole, ownStaff));
        Mockito.when(staffRepository.findByClubId(managedClub.getId())).thenReturn(List.of(ownStaff));
        stubCandidates(StaffCandidate.free("Livre da Silva", StaffQuality.BOM,
                new BigDecimal("45000.00"), 13, 12));

        staffService.getCandidates(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmployedStaff>> employed = ArgumentCaptor.forClass(List.class);
        Mockito.verify(staffCandidateGenerator).generate(eq(saveGameId), eq(managedClub.getId()),
                eq(StaffRole.OLHEIRO), any(), anyInt(), any(), employed.capture());
        // Your own staff are not poaching targets, and neither is a rival in a different role.
        assertThat(employed.getValue()).singleElement().satisfies(e -> {
            assertThat(e.staffId()).isEqualTo(poachable.getId());
            assertThat(e.name()).isEqualTo("Rival Contratado");
            assertThat(e.clubName()).isEqualTo("Avaí Futebol Clube");
            assertThat(e.primaryAttribute()).isEqualTo(16);
            assertThat(e.secondaryAttribute()).isEqualTo(15);
        });
    }

    @Test
    void onlyNamesAtTheHiringClubAreExcludedFromTheCandidateList() {
        stubSave();
        Staff ownStaff = Staff.create(saveGameId, managedClub.getId(), StaffRole.ASSISTENTE,
                StaffQuality.BOM, "Auxiliar da Casa", new BigDecimal("45000.00"), 13, 12);
        Mockito.when(staffRepository.findBySaveGameId(saveGameId))
                .thenReturn(List.of(ownStaff, rivalScout()));
        Mockito.when(staffRepository.findByClubId(managedClub.getId())).thenReturn(List.of(ownStaff));
        stubCandidates(StaffCandidate.free("Livre da Silva", StaffQuality.BOM,
                new BigDecimal("45000.00"), 13, 12));

        staffService.getCandidates(saveGameId, managedClub.getId(), StaffRole.OLHEIRO, userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluded = ArgumentCaptor.forClass(Set.class);
        Mockito.verify(staffCandidateGenerator).generate(any(), any(), any(), any(), anyInt(),
                excluded.capture(), any());
        // Save-wide exclusion would filter out every rival's staff and make poaching impossible.
        assertThat(excluded.getValue()).containsExactly("Auxiliar da Casa");
    }

    @Test
    void aPoachedCandidateIsReportedWithTheClubTheyWouldLeave() {
        stubSave();
        Staff poached = rivalScout();
        Mockito.when(staffRepository.findBySaveGameId(saveGameId)).thenReturn(List.of(poached));
        stubCandidates(
                StaffCandidate.free("Livre da Silva", StaffQuality.BOM, new BigDecimal("45000.00"), 13, 12),
                new StaffCandidate("Rival Contratado", StaffQuality.EXCELENTE,
                        new BigDecimal("80000.00"), 16, 15, poached.getId(), "Avaí Futebol Clube"));

        List<StaffCandidateResponse> candidates = staffService.getCandidates(
                saveGameId, managedClub.getId(), StaffRole.OLHEIRO, userId);

        assertThat(candidates).extracting(StaffCandidateResponse::currentClubName)
                .containsExactly(null, "Avaí Futebol Clube");
        assertThat(candidates).extracting(StaffCandidateResponse::index).containsExactly(0, 1);
    }
}
