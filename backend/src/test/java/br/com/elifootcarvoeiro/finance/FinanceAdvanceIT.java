package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.match.SimulateRoundResponse;
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

    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("finadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void advancingReturnsManagedFinanceBreakdownAndMovesTheBalance() {
        // Given a fresh career
        Career c = createCareer();

        // When advancing one round
        SimulateRoundResponse response = roundSimulationService.simulateNextRound(c.id(), c.userId());

        // Then the response carries the managed finance breakdown matching the DB balance
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
        // Given a career advanced to the final round
        Career c = createCareer();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        SimulateRoundResponse last = null;
        for (int i = 0; i < totalRounds; i++) {
            last = roundSimulationService.simulateNextRound(c.id(), c.userId());
        }

        // Then the final advance reports the season finished, prize money, and the post-prize balance
        assertThat(last).isNotNull();
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(BigDecimal.ZERO);
        assertThat(last.finance().balance()).isEqualByComparingTo(
                clubRepository.findById(c.clubId()).orElseThrow().getBalance());
    }
}
