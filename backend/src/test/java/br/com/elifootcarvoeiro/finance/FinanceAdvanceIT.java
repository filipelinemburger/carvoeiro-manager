package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
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
    void aMatchAdvanceReturnsManagedFinanceBreakdownAndMovesTheBalance() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing to the first MATCH event
        AdvanceResponse response = advanceToNextMatch(c);

        // Then the response carries the managed finance breakdown matching the DB balance
        assertThat(response.type()).isEqualTo("MATCH");
        assertThat(response.finance()).isNotNull();
        assertThat(response.finance().net()).isEqualByComparingTo(
                response.finance().gate().add(response.finance().sponsorship())
                        .subtract(response.finance().wages()).subtract(response.finance().upkeep()));
        assertThat(response.finance().prize()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
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
}
