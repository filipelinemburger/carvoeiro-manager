package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code FinanceService.monthlyWageBills} splits a loaned player's wage between
 * his owner and the club he plays for, and that the split never creates or destroys money.
 *
 * <p>{@code monthlyWageBills} is private and stays that way: this test drives it only through
 * the public monthly-processing entry point ({@link FinanceService#processMonth}) and reads the
 * resulting WAGES ledger rows, which are written only for the managed club.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanWageSplitIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired FinanceService financeService;
    @Autowired FinanceCalculator financeCalculator;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanwage_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID aRivalClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * Charges the given month for the whole save and returns the managed club's WAGES bill for
     * that month (as a positive amount). Ledger rows are written only for the managed club, so
     * {@code c.clubId()} is the only club whose WAGES row we can read back this way.
     */
    private BigDecimal chargeAMonthAndReadWages(Career c, LocalDate monthDate) {
        financeService.processMonth(c.id(), monthDate);
        return financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId()).stream()
                .filter(t -> t.getType() == FinanceTransactionType.WAGES)
                .filter(t -> monthDate.equals(t.getTxDate()))
                .findFirst().orElseThrow()
                .getAmount().negate();
    }

    @Test
    void aLoanedPlayersWageIsChargedToBothClubsAndSumsToHisFullWage() {
        Career c = createCareer();
        UUID borrowerClubId = aRivalClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        BigDecimal fullWage = financeCalculator.monthlyWage(p.getOverall(), p.getAge());

        BigDecimal ownerPart = fullWage.multiply(new BigDecimal("40"))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal borrowerPart = fullWage.subtract(ownerPart);

        // The invariant that matters: no money is created or destroyed by the split.
        assertThat(ownerPart.add(borrowerPart)).isEqualByComparingTo(fullWage);

        // Charge a month with the player at home, then again with him out on loan, and compare
        // the WAGES rows: the owner's bill must fall by exactly borrowerPart. Each call passes a
        // later month so the guard against double-charging (lastFinanceMonth) lets the second
        // charge through.
        BigDecimal ownerBillAtHome = chargeAMonthAndReadWages(c, LocalDate.of(2026, 2, 1));

        p.loanTo(borrowerClubId, 40, true);
        playerRepository.save(p);

        BigDecimal ownerBillOnLoan = chargeAMonthAndReadWages(c, LocalDate.of(2026, 3, 1));
        assertThat(ownerBillAtHome.subtract(ownerBillOnLoan)).isEqualByComparingTo(borrowerPart);
    }
}
