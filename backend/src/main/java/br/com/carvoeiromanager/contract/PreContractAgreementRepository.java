package br.com.carvoeiromanager.contract;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PreContractAgreementRepository extends JpaRepository<PreContractAgreement, UUID> {

    List<PreContractAgreement> findBySaveGameId(UUID saveGameId);

    List<PreContractAgreement> findBySaveGameIdAndStatus(UUID saveGameId, PreContractStatus status);

    List<PreContractAgreement> findBySaveGameIdAndPlayerIdAndStatusIn(
            UUID saveGameId, UUID playerId, Collection<PreContractStatus> statuses);

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
