package br.com.elifootcarvoeiro.contract;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.transfer.TransferService;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FreeAgentListIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired ContractService contractService;
    @Autowired TransferService transferService;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("fa_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void careerCreationSeedsAFreeAgentPool() {
        Career c = createCareer();
        var pool = contractService.getFreeAgents(c.id(), c.userId());
        assertThat(pool).hasSize(10);
        assertThat(pool).allMatch(fa -> fa.wageDemand().signum() > 0);
    }

    @Test
    void freeAgentsDoNotAppearInTheBuyMarket() {
        Career c = createCareer();
        var faIds = contractService.getFreeAgents(c.id(), c.userId()).stream()
                .map(FreeAgentResponse::playerId).toList();
        var market = transferService.getMarket(c.id(), c.userId());
        assertThat(market).noneMatch(m -> faIds.contains(m.playerId()));
    }

    @Test
    void foreignUserCannotListFreeAgents() {
        Career owner = createCareer();
        Career intruder = createCareer();
        assertThatThrownBy(() -> contractService.getFreeAgents(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
