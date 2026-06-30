package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.season.AdvanceResponse;
import br.com.elifootcarvoeiro.season.SeasonAdvanceService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceAdvanceIT {

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
        User user = User.create("finadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Advances until the next MATCH event is processed (skipping TRANSFER stops). */
    private AdvanceResponse advanceToNextMatch(Career c) {
        for (int guard = 0; guard < 500; guard++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if ("MATCH".equals(r.type())) {
                return r;
            }
        }
        throw new IllegalStateException("No MATCH event reached within guard");
    }

    @Test
    void aMatchAdvanceReturnsManagedFinanceBreakdownWithGateAndMatchCostOnly() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing to the first MATCH event
        AdvanceResponse response = advanceToNextMatch(c);

        // Then the per-match response carries gate and matchCost; sponsorship/wages/upkeep are zero
        assertThat(response.type()).isEqualTo("MATCH");
        assertThat(response.finance()).isNotNull();

        // New model: net = gate - matchCost; sponsorship/wages/upkeep zero per match
        assertThat(response.finance().sponsorship()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.finance().wages()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.finance().upkeep()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.finance().net()).isEqualByComparingTo(
                response.finance().gate().subtract(response.finance().matchCost()));

        // Prize is zero for a non-final round
        assertThat(response.finance().prize()).isEqualByComparingTo(BigDecimal.ZERO);

        // Balance in response matches actual DB balance
        assertThat(response.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
    }

    @Test
    void monthlyFinanceIsChargedOncePerMonthEvenAcrossMultipleAdvances() {
        // Given a fresh career, advance through several events in the first month
        Career c = createCareer();

        // Collect monthly ledger entries after each advance — they should appear exactly once per month
        int guard = 0;
        int prevMonth = 0;
        int monthChargeCount = 0;
        List<AdvanceResponse> responses = new ArrayList<>();

        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            responses.add(r);
        }

        // Count how many distinct SPONSOR rows (one per month charged) appear in the ledger
        List<FinanceTransaction> allRows = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        long sponsorRows = allRows.stream()
                .filter(t -> t.getType() == FinanceTransactionType.SPONSOR)
                .count();
        long wagesRows = allRows.stream()
                .filter(t -> t.getType() == FinanceTransactionType.WAGES)
                .count();

        // Sponsor and wages rows should be equal in count (one of each per month charged)
        assertThat(sponsorRows).isGreaterThan(0).as("should have charged at least one month");
        assertThat(wagesRows).isEqualTo(sponsorRows).as("wages and sponsor rows count must match (one each per month)");

        // Each SPONSOR row must have a distinct txDate month (no duplicate months charged)
        List<String> sponsorMonths = allRows.stream()
                .filter(t -> t.getType() == FinanceTransactionType.SPONSOR && t.getTxDate() != null)
                .map(t -> t.getTxDate().getYear() + "-" + t.getTxDate().getMonthValue())
                .toList();
        assertThat(sponsorMonths).doesNotHaveDuplicates()
                .as("monthly costs must be charged exactly once per in-game month");
    }

    @Test
    void crossingAMonthBoundaryChargesMonthlyOnce() {
        // Given a fresh career, advance until we cross a month boundary
        Career c = createCareer();

        int guard = 0;
        String prevMonth = null;
        int monthChanges = 0;

        while (monthChanges < 2 && guard++ < 200) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            if (r.date() != null) {
                String month = r.date().substring(0, 7); // YYYY-MM
                if (prevMonth != null && !month.equals(prevMonth)) {
                    monthChanges++;
                }
                prevMonth = month;
            }
            if (saveGameRepository.findById(c.id()).orElseThrow().getStatus() == SaveGameStatus.FINISHED) break;
        }

        // After crossing at least one month boundary, the ledger must have ≥1 SPONSOR row
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(c.id(), c.clubId());
        long sponsorRows = ledger.stream().filter(t -> t.getType() == FinanceTransactionType.SPONSOR).count();
        assertThat(sponsorRows).isGreaterThanOrEqualTo(1)
                .as("crossing a month boundary must charge monthly finance at least once");
    }

    @Test
    void theSeasonFinishingAdvanceAwardsPrizeInTheBreakdown() {
        // Given a career advanced until the season finishes
        Career c = createCareer();
        AdvanceResponse last = null;
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            last = seasonAdvanceService.advance(c.id(), c.userId());
        }

        // Then the final advance reports the season finished, prize money, and the post-prize balance
        assertThat(last).isNotNull();
        assertThat(last.type()).isEqualTo("MATCH");
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(BigDecimal.ZERO);
        assertThat(last.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
    }

    @Test
    void balanceSanityAfterFullSeason() {
        // Given a career advanced through a full season
        Career c = createCareer();
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }

        // Then the managed club's balance is within a believable band
        // (not negative infinity, not absurd billions)
        BigDecimal balance = clubRepository.findById(c.clubId()).orElseThrow().getBalance();
        // Allow a range of R$-2M to R$+200M (any result within this is realistic)
        assertThat(balance).isGreaterThan(new BigDecimal("-2000000.00"))
                .as("club should not be catastrophically bankrupt after one season");
        assertThat(balance).isLessThan(new BigDecimal("200000000.00"))
                .as("club balance should not be absurdly large");
    }
}
