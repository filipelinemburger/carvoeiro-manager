package br.com.elifootcarvoeiro.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferOfferRepository extends JpaRepository<TransferOffer, UUID> {
    List<TransferOffer> findBySaveGameIdAndStatusIn(UUID saveGameId, Collection<TransferOfferStatus> statuses);
    void deleteBySaveGameId(UUID saveGameId);
}
