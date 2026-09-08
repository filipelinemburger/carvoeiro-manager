package br.com.carvoeiromanager.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LoanOfferRepository extends JpaRepository<LoanOffer, UUID> {
    /**
     * The save's own open loan offers, for the GET listing — every {@code LoanOffer} row is
     * already one of the manager's own negotiations (nothing here is AI-to-AI, unlike transfers),
     * so no direction/club filter is needed on top of the status one.
     */
    List<LoanOffer> findBySaveGameIdAndStatusIn(UUID saveGameId, Collection<LoanOfferStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
