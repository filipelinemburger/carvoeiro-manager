package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping("/free-agents")
    public ResponseEntity<List<FreeAgentResponse>> freeAgents(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.getFreeAgents(saveGameId, userId));
    }

    @PostMapping("/players/{playerId}/renew")
    public ResponseEntity<PlayerContractResponse> renew(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody RenewContractRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.renewContract(
                saveGameId, userId, playerId, request.wage(), request.contractYears()));
    }

    @PostMapping("/free-agents/{playerId}/sign")
    public ResponseEntity<PlayerContractResponse> sign(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody SignFreeAgentRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.signFreeAgent(
                saveGameId, userId, playerId, request.wage(), request.contractYears()));
    }

    @PostMapping("/players/{playerId}/terminate")
    public ResponseEntity<TerminationResponse> terminate(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(contractService.terminateContract(saveGameId, userId, playerId));
    }
}
