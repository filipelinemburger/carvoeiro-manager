package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping("/players/{playerId}/renew")
    public ResponseEntity<PlayerContractResponse> renew(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody RenewContractRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.renewContract(
                saveGameId, userId, playerId, request.wage(), request.contractYears()));
    }
}
