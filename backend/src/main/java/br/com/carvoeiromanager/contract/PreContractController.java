package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/pre-contracts")
public class PreContractController {

    private final PreContractService preContractService;

    public PreContractController(PreContractService preContractService) {
        this.preContractService = preContractService;
    }

    @GetMapping("/eligible")
    public ResponseEntity<List<PreContractEligibleResponse>> eligible(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.getEligiblePlayers(saveGameId, userId));
    }

    @PostMapping("/offers")
    public ResponseEntity<PreContractOfferResponse> offer(
            @PathVariable UUID saveGameId, @RequestBody PreContractOfferRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.makeOffer(
                saveGameId, userId, request.playerId(), request.wage(), request.contractYears()));
    }

    @PostMapping("/offers/{playerId}/accept-counter")
    public ResponseEntity<PreContractOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId,
            @RequestBody AcceptCounterRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.acceptCounter(
                saveGameId, userId, playerId, request.contractYears()));
    }

    @GetMapping("/agreements")
    public ResponseEntity<List<PreContractAgreementResponse>> agreements(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(preContractService.getAgreements(saveGameId, userId));
    }

    @DeleteMapping("/agreements/{agreementId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable UUID saveGameId, @PathVariable UUID agreementId,
            @CurrentUser UUID userId) {
        preContractService.withdraw(saveGameId, userId, agreementId);
        return ResponseEntity.noContent().build();
    }
}
