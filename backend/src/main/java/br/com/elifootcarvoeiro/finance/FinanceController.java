package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/clubs/{clubId}/finances")
public class FinanceController {

    private final FinanceService financeService;

    public FinanceController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public ResponseEntity<FinancesResponse> getFinances(
            @PathVariable UUID saveGameId, @PathVariable UUID clubId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(financeService.getFinances(saveGameId, clubId, userId));
    }
}
