package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonRolloverService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceRolloverIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void rolloverPurgesTheSeasonLedgerAndCarriesBalanceForward() {
        // Given a fully-played season (which accrued managed-club ledger rows)
        Career c = createCareer();
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()))
                .as("ledger accrued during the season").isNotEmpty();
        BigDecimal balanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        // When the season rolls over
        rolloverService.rollover(c.id(), c.userId());

        // Then the ledger is purged and the balance carried forward unchanged
        assertThat(financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId())).isEmpty();
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
    }

    @Test
    void rolloverResetsLastFinanceMonthSoNewSeasonChargesMonthlyFresh() {
        // Given a fully-played season (lastFinanceMonth > 0 after advance)
        Career c = createCareer();
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }

        // Verify lastFinanceMonth was set during advance
        SaveGame beforeRollover = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(beforeRollover.getLastFinanceMonth()).isGreaterThan(0)
                .as("lastFinanceMonth should be non-zero after advancing through the season");

        // When the season rolls over
        rolloverService.rollover(c.id(), c.userId());

        // Then lastFinanceMonth is reset to 0
        SaveGame afterRollover = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(afterRollover.getLastFinanceMonth()).isEqualTo(0)
                .as("lastFinanceMonth must be reset to 0 after rollover so the new season charges fresh");

        // And the new season can accrue monthly charges again (first advance triggers month charge)
        int ledgerSizeBefore = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()).size();

        // Advance once in the new season to trigger the first monthly finance
        seasonAdvanceService.advance(c.id(), c.userId());

        int ledgerSizeAfter = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()).size();
        assertThat(ledgerSizeAfter).isGreaterThan(ledgerSizeBefore)
                .as("new season's first advance should produce monthly finance rows");
    }
}
