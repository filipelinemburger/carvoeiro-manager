package br.com.carvoeiromanager.loan;

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
@RequestMapping("/api/v1/save-games/{saveGameId}/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping("/out")
    public ResponseEntity<LoanOfferResponse> loanOut(
            @PathVariable UUID saveGameId, @RequestBody LoanOutRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.offerLoanOut(saveGameId, userId, request.playerId(),
                request.borrowerClubId(), request.ownerWageShare(), request.mayFaceOwner()));
    }

    @PostMapping("/in")
    public ResponseEntity<LoanOfferResponse> loanIn(
            @PathVariable UUID saveGameId, @RequestBody LoanInRequest request,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.offerLoanIn(saveGameId, userId, request.playerId(),
                request.ownerWageShare(), request.mayFaceOwner()));
    }

    @PostMapping("/{offerId}/accept-counter")
    public ResponseEntity<LoanOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.acceptCounter(saveGameId, userId, offerId));
    }

    /** The save's open loan offers (COUNTERED) — reachable after the dialog that started
     * one has closed, so a countered offer's {@code offerId} is never lost. */
    @GetMapping
    public ResponseEntity<List<LoanOfferResponse>> offers(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.getOffers(saveGameId, userId));
    }

    @PostMapping("/{offerId}/withdraw")
    public ResponseEntity<LoanOfferResponse> withdraw(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(loanService.withdrawOffer(saveGameId, userId, offerId));
    }
}
