package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.common.PageResponse;
import br.com.carvoeiromanager.common.security.CurrentUser;
import br.com.carvoeiromanager.player.Position;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/market")
    public ResponseEntity<PageResponse<MarketPlayerResponse>> market(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID clubId,
            @RequestParam(required = false) Position position,
            @RequestParam(required = false) Integer ageMin,
            @RequestParam(required = false) Integer ageMax,
            @RequestParam(required = false) Integer overallMin,
            @RequestParam(required = false) Integer overallMax,
            @RequestParam(required = false) BigDecimal valueMin,
            @RequestParam(required = false) BigDecimal valueMax) {
        MarketFilter filter = new MarketFilter(clubId, position, ageMin, ageMax,
                overallMin, overallMax, valueMin, valueMax);
        return ResponseEntity.ok(transferService.getMarket(saveGameId, userId, filter, page, size));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<TransferOfferResponse>> offers(
            @PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.getOffers(saveGameId, userId));
    }

    @PostMapping("/offers")
    public ResponseEntity<TransferOfferResponse> makeOffer(
            @PathVariable UUID saveGameId, @RequestBody MakeOfferRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.makeOffer(
                saveGameId, userId, request.playerId(), request.amount(),
                request.wage(), request.contractYears()));
    }

    @PostMapping("/offers/{offerId}/accept-counter")
    public ResponseEntity<TransferOfferResponse> acceptCounter(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.acceptCounter(saveGameId, userId, offerId));
    }

    @PostMapping("/offers/{offerId}/withdraw")
    public ResponseEntity<TransferOfferResponse> withdraw(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.withdrawOffer(saveGameId, userId, offerId));
    }

    @PostMapping("/offers/{offerId}/respond")
    public ResponseEntity<TransferOfferResponse> respond(
            @PathVariable UUID saveGameId, @PathVariable UUID offerId,
            @RequestBody RespondOfferRequest request, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.respondToIncoming(
                saveGameId, userId, offerId, request.action(), request.counterAmount()));
    }

    @PutMapping("/listings/{playerId}")
    public ResponseEntity<TransferListingResponse> listPlayer(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.listPlayer(saveGameId, userId, playerId));
    }

    @DeleteMapping("/listings/{playerId}")
    public ResponseEntity<TransferListingResponse> unlistPlayer(
            @PathVariable UUID saveGameId, @PathVariable UUID playerId, @CurrentUser UUID userId) {
        return ResponseEntity.ok(transferService.unlistPlayer(saveGameId, userId, playerId));
    }
}
