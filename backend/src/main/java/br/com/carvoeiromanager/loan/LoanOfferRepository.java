package br.com.carvoeiromanager.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LoanOfferRepository extends JpaRepository<LoanOffer, UUID> {
    List<LoanOffer> findBySaveGameIdAndPlayerIdAndStatusIn(UUID saveGameId, UUID playerId,
                                                           Collection<LoanOfferStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
