package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
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

    public StaffService(SaveGameService saveGameService, StaffRepository staffRepository,
                        StaffCandidateGenerator staffCandidateGenerator) {
        this.saveGameService = saveGameService;
        this.staffRepository = staffRepository;
        this.staffCandidateGenerator = staffCandidateGenerator;
    }

    public StaffRosterResponse getRoster(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Map<StaffRole, Staff> byRole = staffRepository.findByClubId(clubId).stream()
                .collect(Collectors.toMap(Staff::getRole, s -> s));
        return toRosterResponse(byRole);
    }

    public List<StaffCandidateResponse> getCandidates(UUID saveGameId, UUID clubId, StaffRole role,
                                                       UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        List<StaffCandidate> candidates = staffCandidateGenerator.generate(
                saveGameId, clubId, role, save.getCurrentDate(), CANDIDATE_COUNT);
        return toCandidateResponses(candidates);
    }

    @Transactional
    public StaffRosterResponse hire(UUID saveGameId, UUID clubId, StaffRole role, int candidateIndex,
                                    UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
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
                chosen.name(), chosen.wage()));

        return getRoster(saveGameId, clubId, userId);
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
                : new StaffResponse(staff.getName(), staff.getQuality().name(), staff.getWage());
    }

    private List<StaffCandidateResponse> toCandidateResponses(List<StaffCandidate> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(i -> {
                    StaffCandidate c = candidates.get(i);
                    return new StaffCandidateResponse(i, c.name(), c.quality().name(), c.wage());
                })
                .toList();
    }
}
