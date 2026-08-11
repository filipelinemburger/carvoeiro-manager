package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.season.MatchPreviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StaffService {

    private static final int CANDIDATE_COUNT = 3;

    private final SaveGameService saveGameService;
    private final StaffRepository staffRepository;
    private final StaffVacancyRepository staffVacancyRepository;
    private final StaffCandidateGenerator staffCandidateGenerator;
    private final ClubRepository clubRepository;
    private final MatchPreviewService matchPreviewService;

    public StaffService(SaveGameService saveGameService, StaffRepository staffRepository,
                        StaffVacancyRepository staffVacancyRepository,
                        StaffCandidateGenerator staffCandidateGenerator, ClubRepository clubRepository,
                        MatchPreviewService matchPreviewService) {
        this.saveGameService = saveGameService;
        this.staffRepository = staffRepository;
        this.staffVacancyRepository = staffVacancyRepository;
        this.staffCandidateGenerator = staffCandidateGenerator;
        this.clubRepository = clubRepository;
        this.matchPreviewService = matchPreviewService;
    }

    public StaffRosterResponse getRoster(UUID saveGameId, UUID clubId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));
        return toRosterResponse(byRole, clubId.equals(save.getManagedClubId()), save.getManagerName());
    }

    public List<StaffCandidateResponse> getCandidates(UUID saveGameId, UUID clubId, StaffRole role,
                                                       UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        requireManagedClub(save, clubId);
        requireHirable(role);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT,
                namesAtClub(clubId), poachTargets(saveGameId, clubId, role));
        return toCandidateResponses(candidates);
    }

    @Transactional
    public StaffRosterResponse hire(UUID saveGameId, UUID clubId, StaffRole role, int candidateIndex,
                                    UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        requireManagedClub(save, clubId);
        requireHirable(role);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT,
                namesAtClub(clubId), poachTargets(saveGameId, clubId, role));
        if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "candidateIndex must be between 0 and " + (candidates.size() - 1));
        }
        StaffCandidate chosen = candidates.get(candidateIndex);

        // Poaching: the rival loses them. Deleted before the insert for the same flush-ordering
        // reason as the replace below — Hibernate runs pending inserts before pending deletes.
        if (chosen.sourceStaffId() != null) {
            poachFrom(saveGameId, chosen.sourceStaffId());
        }

        // Flush the delete before queuing the insert: Hibernate's flush order runs pending
        // inserts before pending deletes, so without this, replacing an already-filled role
        // would try to insert the new row while the old (club_id, role) row is still present,
        // tripping the staff_club_id_role_key unique constraint.
        staffRepository.deleteByClubIdAndRole(clubId, role);
        staffRepository.flush();
        staffRepository.save(Staff.create(saveGameId, clubId, role, chosen.quality(),
                chosen.name(), chosen.wage(), chosen.primaryAttribute(), chosen.secondaryAttribute()));

        // Build the response directly rather than calling getRoster(...) again: ownership and
        // save-game membership were already established above, so re-running requireOwned/
        // requireClubInSave here would just repeat the same two checks for no benefit.
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));

        if (role == StaffRole.ASSISTENTE) {
            // A newly hired auxiliar immediately delivers the pending pre-match analysis.
            matchPreviewService.maybePersistPreview(saveGameId);
        }

        return toRosterResponse(byRole, true, save.getManagerName());
    }

    /**
     * Takes a staff member off the club that employs them, and records the hole they leave behind.
     *
     * <p>The vacancy is what stops the theft from BENEFITING the victim. {@code StaffEffectsService}
     * floors an AI club's empty slot at a research baseline of 14.0, and most poachable staff score
     * below that, so a bare delete used to hand the robbed club an upgrade it keeps forever. The
     * source club and role are read off the persisted row rather than off the candidate: the
     * candidate is a rolled, unpersisted view whose {@code sourceStaffId} is the only part of it the
     * database has ever seen.
     *
     * <p>The lookup is also the existence check. {@code deleteById} on a missing id is a silent
     * no-op in this Spring Data version, which made a vanished target indistinguishable from a
     * successful poach — the caller got a roster back saying they had signed someone nobody
     * employed. Rejecting outright is the honest answer; browsing again re-rolls the shortlist
     * against the market as it now stands.
     */
    private void poachFrom(UUID saveGameId, UUID sourceStaffId) {
        final Staff source = staffRepository.findById(sourceStaffId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "The chosen candidate is no longer employed at that club — browse again"));
        recordVacancy(saveGameId, source.getClubId(), source.getRole());
        staffRepository.deleteById(source.getId());
    }

    /**
     * Idempotent by design: {@code staff_vacancy} is UNIQUE on (club_id, role), and a second poach
     * of the same slot cannot happen today only because nothing ever refills it. Guarding here
     * keeps that an implementation detail of the AI rather than a constraint violation waiting for
     * the day AI clubs start hiring.
     */
    private void recordVacancy(UUID saveGameId, UUID clubId, StaffRole role) {
        if (!staffVacancyRepository.existsByClubIdAndRole(clubId, role)) {
            staffVacancyRepository.save(StaffVacancy.create(saveGameId, clubId, role));
        }
    }

    /**
     * Enforces that {@code clubId} actually belongs to {@code saveGameId} — without this,
     * saveGameId ownership alone would let a caller read or hire staff against a clubId from a
     * different save game (their own second career, or another user's, if the UUID were known).
     * Mirrors {@code LineupService.requireOwnedClub}.
     */
    private void requireClubInSave(UUID saveGameId, UUID clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found"));
        if (!club.getSaveGameId().equals(saveGameId)) {
            throw new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found");
        }
    }

    /** The head coach is the player — there is nobody to browse and nobody to hire. */
    private void requireHirable(StaffRole role) {
        if (!StaffRole.HIRABLE.contains(role)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Role " + role + " cannot be hired");
        }
    }

    /**
     * Browsing and hiring only ever apply to your own club. {@code requireClubInSave} alone is not
     * enough — it permits any club in the caller's save, which would let a crafted request staff a
     * rival at that rival's expense.
     */
    private void requireManagedClub(SaveGame save, UUID clubId) {
        if (!clubId.equals(save.getManagedClubId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Staff can only be managed for your own club");
        }
    }

    /**
     * Names already spoken for at the hiring club. Deliberately NOT save-wide: staff employed at
     * other clubs are poaching targets, not exclusions.
     */
    private Set<String> namesAtClub(UUID clubId) {
        return staffRepository.findByClubId(clubId).stream()
                .map(Staff::getName)
                .collect(Collectors.toSet());
    }

    /** Real professionals working elsewhere in this save, for the given role. */
    private List<EmployedStaff> poachTargets(UUID saveGameId, UUID hiringClubId, StaffRole role) {
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        return staffRepository.findBySaveGameId(saveGameId).stream()
                .filter(s -> s.getRole() == role)
                .filter(s -> !s.getClubId().equals(hiringClubId))
                .map(s -> new EmployedStaff(s.getId(), s.getName(),
                        clubNames.getOrDefault(s.getClubId(), "—"),
                        s.getPrimaryAttribute(), s.getSecondaryAttribute()))
                .toList();
    }

    private StaffRosterResponse toRosterResponse(Map<StaffRole, Staff> byRole, boolean managedClub,
                                                 String managerName) {
        // At the managed club the head coach is the player, so no row exists (see
        // SaveGameService.seedClubStaff) and the slot is filled from the manager persona instead.
        StaffResponse tecnico = managedClub
                ? new StaffResponse(managerName, null, null, 0, 0)
                : toStaffResponse(byRole.get(StaffRole.TECNICO));
        return new StaffRosterResponse(
                tecnico,
                toStaffResponse(byRole.get(StaffRole.ASSISTENTE)),
                toStaffResponse(byRole.get(StaffRole.PREPARADOR_FISICO)),
                toStaffResponse(byRole.get(StaffRole.OLHEIRO)),
                toStaffResponse(byRole.get(StaffRole.FISIOTERAPEUTA)));
    }

    private StaffResponse toStaffResponse(Staff staff) {
        return staff == null ? null
                : new StaffResponse(staff.getName(), staff.getQuality().name(), staff.getWage(),
                        staff.getPrimaryAttribute(), staff.getSecondaryAttribute());
    }

    private List<StaffCandidateResponse> toCandidateResponses(List<StaffCandidate> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(i -> {
                    StaffCandidate c = candidates.get(i);
                    return new StaffCandidateResponse(i, c.name(), c.quality().name(), c.wage(),
                            c.primaryAttribute(), c.secondaryAttribute(), c.currentClubName());
                })
                .toList();
    }
}
