package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ResponseEntity<StaffRosterResponse> getRoster(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.getRoster(saveGameId, clubId, userId));
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<StaffCandidateResponse>> getCandidates(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId,
            @RequestParam StaffRole role, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.getCandidates(saveGameId, clubId, role, userId));
    }

    @PutMapping("/{role}")
    public ResponseEntity<StaffRosterResponse> hire(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @PathVariable StaffRole role,
            @Valid @RequestBody HireStaffRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(staffService.hire(
                saveGameId, clubId, role, request.candidateIndex(), request.candidateName(), userId));
    }
}
