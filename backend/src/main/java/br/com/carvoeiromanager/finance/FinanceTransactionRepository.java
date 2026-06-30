package br.com.carvoeiromanager.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinanceTransactionRepository extends JpaRepository<FinanceTransaction, UUID> {
    List<FinanceTransaction> findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(UUID saveGameId, UUID clubId);
    void deleteBySaveGameId(UUID saveGameId);
}
