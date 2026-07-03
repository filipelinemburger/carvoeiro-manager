package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/youth-prospects")
public class YouthController {

    private final YouthAcademyService youthAcademyService;

    public YouthController(YouthAcademyService youthAcademyService) {
        this.youthAcademyService = youthAcademyService;
    }

    @GetMapping
    public List<YouthProspectResponse> list(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return youthAcademyService.listProspects(saveGameId, userId);
    }

    @PostMapping("/{prospectId}/promote")
    public YouthProspectResponse promote(@PathVariable UUID saveGameId, @PathVariable UUID prospectId,
                                         @CurrentUser UUID userId) {
        return youthAcademyService.promote(saveGameId, prospectId, userId);
    }

    @PostMapping("/{prospectId}/release")
    public ResponseEntity<Void> release(@PathVariable UUID saveGameId, @PathVariable UUID prospectId,
                                        @CurrentUser UUID userId) {
        youthAcademyService.release(saveGameId, prospectId, userId);
        return ResponseEntity.noContent().build();
    }
}
