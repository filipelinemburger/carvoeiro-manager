package br.com.elifootcarvoeiro.transfer;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.contract.ContractValuation;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
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
class TransferTermsIT {

    @Autowired TransferService transferService;
    @Autowired TransferValuation transferValuation;
    @Autowired ContractValuation contractValuation;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User u = User.create("terms_" + UUID.randomUUID(), "hash");
        userRepository.save(u);
        return new Career(u.getId(), saveGameService.createCareer(u.getId()));
    }

    private Player anAiPlayer(Career c) {
        UUID other = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        return playerRepository.findByClubId(other).get(0);
    }

    @Test
    void acceptableTermsSetTheBoughtPlayersContract() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal wage = contractValuation.wageDemand(t.getOverall(), t.getAge());

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, wage, 4);

        assertThat(r.status()).isEqualTo("ACCEPTED");
        Player bought = playerRepository.findById(t.getId()).orElseThrow();
        assertThat(bought.getClubId()).isEqualTo(c.clubId());
        assertThat(bought.getWage()).isEqualByComparingTo(wage);
        assertThat(bought.getContractYears()).isEqualTo(4);
    }

    @Test
    void aLowballWageMakesTheDealFallThrough() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal demand = contractValuation.wageDemand(t.getOverall(), t.getAge());
        BigDecimal lowWage = demand.subtract(new BigDecimal("1"));
        BigDecimal buyerBalanceBefore = clubRepository.findById(c.clubId()).orElseThrow().getBalance();

        TransferOfferResponse r = transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, lowWage, 3);

        assertThat(r.status()).isEqualTo("REJECTED");
        // No money moved; player stayed at the seller.
        assertThat(clubRepository.findById(c.clubId()).orElseThrow().getBalance())
                .isEqualByComparingTo(buyerBalanceBefore);
        assertThat(playerRepository.findById(t.getId()).orElseThrow().getClubId())
                .isNotEqualTo(c.clubId());
    }

    @Test
    void anInvalidLengthIsRejectedUpFront() {
        Career c = createCareer();
        Player t = anAiPlayer(c);
        BigDecimal fee = transferValuation.marketValue(t.getOverall(), t.getAge());
        BigDecimal wage = contractValuation.wageDemand(t.getOverall(), t.getAge());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> transferService.makeOffer(c.id(), c.userId(), t.getId(), fee, wage, 0))
                .satisfies(ex -> assertThat(((br.com.elifootcarvoeiro.common.ApiException) ex).getCode())
                        .isEqualTo("INVALID_CONTRACT"));
    }
}
