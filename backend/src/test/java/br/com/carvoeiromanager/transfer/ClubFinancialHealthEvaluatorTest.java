package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.finance.WageBillCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.DESPERATE;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.HEALTHY;
import static br.com.carvoeiromanager.transfer.ClubFinancialHealthEvaluator.FinancialHealth.TIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClubFinancialHealthEvaluatorTest {

    @Mock PlayerRepository playerRepository;

    private final FinanceCalculator financeCalculator = new FinanceCalculator();
    private final WageBillCalculator wageBillCalculator = new WageBillCalculator(financeCalculator);
    private ClubFinancialHealthEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ClubFinancialHealthEvaluator(playerRepository, wageBillCalculator);
    }

    /** One overall-60, age-25 player: monthlyWage = weeklyWage(60,25)*4 = 72000*4 = 288000. */
    private Player onePlayer(UUID saveGameId, UUID clubId) {
        return Player.create(saveGameId, clubId, "P", Position.MID,
                25, "Brasil", 60, 60, 60, 60, 10, 60, 60);
    }

    private Club clubWithBalance(UUID saveGameId, BigDecimal balance) {
        Club club = Club.create(saveGameId, "Club", "CLB");
        club.setBalance(balance);
        return club;
    }

    @Test
    void balanceBelowOneMonthlyWageBillIsDesperate() {
        UUID saveGameId = UUID.randomUUID();
        Club club = clubWithBalance(saveGameId, new BigDecimal("287999.99")); // just under 1x bill (288000)
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(onePlayer(saveGameId, club.getId())));

        assertThat(evaluator.evaluate(club)).isEqualTo(DESPERATE);
    }

    @Test
    void balanceAtExactlyOneMonthlyWageBillIsNotDesperate() {
        UUID saveGameId = UUID.randomUUID();
        Club club = clubWithBalance(saveGameId, new BigDecimal("288000.00")); // exactly 1x bill
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(onePlayer(saveGameId, club.getId())));

        assertThat(evaluator.evaluate(club)).isNotEqualTo(DESPERATE);
        assertThat(evaluator.evaluate(club)).isEqualTo(TIGHT);
    }

    @Test
    void balanceBelowThreeMonthlyWageBillsIsTight() {
        UUID saveGameId = UUID.randomUUID();
        Club club = clubWithBalance(saveGameId, new BigDecimal("863999.99")); // just under 3x bill (864000)
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(onePlayer(saveGameId, club.getId())));

        assertThat(evaluator.evaluate(club)).isEqualTo(TIGHT);
    }

    @Test
    void balanceAtExactlyThreeMonthlyWageBillsIsHealthy() {
        UUID saveGameId = UUID.randomUUID();
        Club club = clubWithBalance(saveGameId, new BigDecimal("864000.00")); // exactly 3x bill
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(onePlayer(saveGameId, club.getId())));

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }

    @Test
    void balanceWellAboveThreeMonthlyWageBillsIsHealthy() {
        UUID saveGameId = UUID.randomUUID();
        Club club = clubWithBalance(saveGameId, new BigDecimal("10000000.00"));
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(onePlayer(saveGameId, club.getId())));

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }

    @Test
    void anEmptySquadIsAlwaysHealthyRegardlessOfBalance() {
        Club club = clubWithBalance(UUID.randomUUID(), BigDecimal.ZERO);
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of());

        assertThat(evaluator.evaluate(club)).isEqualTo(HEALTHY);
    }

    /**
     * Regression for the finding that the evaluator summed the FULL wage of everyone playing at
     * a club, ignoring the loan split {@code FinanceService} actually charges: the borrower was
     * evaluated on a wage it does not pay (over-stated) and the owner was evaluated without the
     * wage share it does pay (under-stated, since a loaned-out player never appears in
     * {@code findByClubId} for his owner at all). Both sides must classify off the split bill.
     */
    @Test
    void borrowerClassifiesFromItsWageShareNotTheFullWage() {
        UUID saveGameId = UUID.randomUUID();
        Club owner = clubWithBalance(saveGameId, BigDecimal.ZERO);
        Club borrower = clubWithBalance(saveGameId, new BigDecimal("250000.00"));
        Player player = onePlayer(saveGameId, owner.getId());
        player.loanTo(borrower.getId(), 40, true); // ownerPart 115200, borrowerPart 172800 of a 288000 wage
        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(player));

        // Full wage (288000) would put a 250000 balance below the DESPERATE line; the borrower's
        // actual share (172800) puts it well clear of DESPERATE, into TIGHT (172800 <= 250000 < 518400).
        assertThat(evaluator.evaluate(borrower)).isEqualTo(TIGHT);
    }

    @Test
    void ownerClassifiesFromItsWageShareNotZero() {
        UUID saveGameId = UUID.randomUUID();
        Club owner = clubWithBalance(saveGameId, new BigDecimal("200000.00"));
        Club borrower = clubWithBalance(saveGameId, BigDecimal.ZERO);
        Player player = onePlayer(saveGameId, owner.getId());
        player.loanTo(borrower.getId(), 40, true); // ownerPart 115200 of a 288000 wage

        when(playerRepository.findBySaveGameId(any())).thenReturn(List.of(player));

        // Ignoring the owner's share entirely (as findByClubId did) would report a zero bill and
        // classify HEALTHY no matter the balance; the owner's actual share (115200) puts a
        // 200000 balance into TIGHT (115200 <= 200000 < 345600).
        assertThat(evaluator.evaluate(owner)).isEqualTo(TIGHT);
    }
}
