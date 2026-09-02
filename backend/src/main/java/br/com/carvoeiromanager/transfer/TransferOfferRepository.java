package br.com.carvoeiromanager.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, UUID> {
    List<TransferOffer> findBySaveGameIdAndStatusIn(UUID saveGameId, Collection<TransferOfferStatus> statuses);

    List<TransferOffer> findBySaveGameIdAndPlayerIdAndStatusIn(UUID saveGameId, UUID playerId,
                                                               Collection<TransferOfferStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
