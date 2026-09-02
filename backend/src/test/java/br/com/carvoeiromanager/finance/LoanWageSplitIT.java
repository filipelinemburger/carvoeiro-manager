package br.com.carvoeiromanager.finance;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
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
 * the public monthly-processing entry point ({@link FinanceService#processMonth}). The owner's
 * side is read off the WAGES ledger row, which is written only for the managed club. The
 * borrower's side is observed through its raw {@link Club#getBalance()} instead (no ledger row
 * exists for a non-managed club) — otherwise a silently-dropped borrower charge would be
 * invisible to this test even though real money vanished every month.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanWageSplitIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;
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

    private BigDecimal balanceOf(UUID clubId) {
        return clubRepository.findById(clubId).orElseThrow().getBalance();
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

        BigDecimal borrowerBalanceBeforeMonth1 = balanceOf(borrowerClubId);

        // Charge a month with the player at home, then again with him out on loan. Each call
        // passes a later month so the guard against double-charging (lastFinanceMonth) lets the
        // second charge through.
        BigDecimal ownerBillAtHome = chargeAMonthAndReadWages(c, LocalDate.of(2026, 2, 1));
        BigDecimal borrowerBalanceAfterMonth1 = balanceOf(borrowerClubId);

        p.loanTo(borrowerClubId, 40, true);
        playerRepository.save(p);

        BigDecimal ownerBillOnLoan = chargeAMonthAndReadWages(c, LocalDate.of(2026, 3, 1));
        BigDecimal borrowerBalanceAfterMonth2 = balanceOf(borrowerClubId);

        // --- Owner side: read straight off his WAGES ledger row (written only for the managed
        // club). His bill must fall by exactly the borrower's share. ---
        assertThat(ownerBillAtHome.subtract(ownerBillOnLoan)).isEqualByComparingTo(borrowerPart);

        // --- Borrower side: no ledger row is written for a non-managed club, so we read its raw
        // balance instead. Its monthly net also includes sponsorship and upkeep, which are
        // identical between the two months (same reputation, same stadium capacity, no other
        // roster change happens between the two calls) -- so the CHANGE in the borrower's net
        // from the control (no-loan) month to the loan month isolates exactly the new wage debit.
        // This closes a blind spot the owner-side assertion alone cannot see: if the borrower-side
        // charge were silently dropped, the owner's bill would still fall by the right amount but
        // the borrower would never actually pay it -- that share of the wage would just vanish.
        BigDecimal borrowerNetAtHome = borrowerBalanceAfterMonth1.subtract(borrowerBalanceBeforeMonth1);
        BigDecimal borrowerNetOnLoan = borrowerBalanceAfterMonth2.subtract(borrowerBalanceAfterMonth1);
        assertThat(borrowerNetAtHome.subtract(borrowerNetOnLoan)).isEqualByComparingTo(borrowerPart);
    }
}
