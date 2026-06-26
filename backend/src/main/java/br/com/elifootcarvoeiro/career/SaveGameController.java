package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games")
public class SaveGameController {

    private final SaveGameService saveGameService;

    public SaveGameController(SaveGameService saveGameService) {
        this.saveGameService = saveGameService;
    }

    @PostMapping
    public ResponseEntity<SaveGameResponse> createCareer(@CurrentUser UUID userId) {
        SaveGameResponse response = saveGameService.createCareer(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SaveGameResponse> getById(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(saveGameService.getById(id, userId));
    }
}
