package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StaffService {

    private static final int CANDIDATE_COUNT = 3;

    private final SaveGameService saveGameService;
    private final StaffRepository staffRepository;
    private final StaffCandidateGenerator staffCandidateGenerator;
    private final ClubRepository clubRepository;

    public StaffService(SaveGameService saveGameService, StaffRepository staffRepository,
                        StaffCandidateGenerator staffCandidateGenerator, ClubRepository clubRepository) {
        this.saveGameService = saveGameService;
        this.staffRepository = staffRepository;
        this.staffCandidateGenerator = staffCandidateGenerator;
        this.clubRepository = clubRepository;
    }

    public StaffRosterResponse getRoster(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));
        return toRosterResponse(byRole);
    }

    public List<StaffCandidateResponse> getCandidates(UUID saveGameId, UUID clubId, StaffRole role,
                                                       UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT);
        return toCandidateResponses(candidates);
    }

    @Transactional
    public StaffRosterResponse hire(UUID saveGameId, UUID clubId, StaffRole role, int candidateIndex,
                                    UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        requireClubInSave(saveGameId, clubId);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT);
        if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "candidateIndex must be between 0 and " + (candidates.size() - 1));
        }
        StaffCandidate chosen = candidates.get(candidateIndex);

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
        return toRosterResponse(byRole);
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

    private StaffRosterResponse toRosterResponse(Map<StaffRole, Staff> byRole) {
        return new StaffRosterResponse(
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
                            c.primaryAttribute(), c.secondaryAttribute());
                })
                .toList();
    }
}
