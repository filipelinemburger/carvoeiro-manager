package br.com.elifootcarvoeiro.finance;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FinanceSchemaIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FinanceTransactionRepository financeTransactionRepository;

    @Test
    void persistsClubBalancePlayerWageAndLedgerRow() {
        // Given a save game with a club, a player, and a finance transaction
        User user = User.create("fin_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2026);
        saveGameRepository.save(save);

        Club club = Club.create(save.getId(), "Test FC", "TST");
        club.setBalance(new BigDecimal("1000000.00"));
        club.setReputation(67);
        club.setStadiumCapacity(41500);
        club.setSponsorshipPerRound(new BigDecimal("536000.00"));
        club.setStadiumUpkeepPerRound(new BigDecimal("166000.00"));
        clubRepository.save(club);

        Player player = Player.create(save.getId(), club.getId(), "Tester", Position.MID,
                25, "Brasil", 60, 55, 70, 65, 10, 72, 66);
        player.setWage(new BigDecimal("84500.00"));
        playerRepository.save(player);

        financeTransactionRepository.save(FinanceTransaction.create(save.getId(), club.getId(),
                2026, 3, FinanceTransactionType.GATE, new BigDecimal("1935000.00"), "Renda de bilheteria"));

        // When reading back
        Club reloaded = clubRepository.findById(club.getId()).orElseThrow();
        Player reloadedPlayer = playerRepository.findById(player.getId()).orElseThrow();
        List<FinanceTransaction> ledger = financeTransactionRepository
                .findBySaveGameIdAndClubIdOrderByRoundDescCreatedAtDesc(save.getId(), club.getId());

        // Then the financial fields round-trip
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1000000.00");
        assertThat(reloaded.getReputation()).isEqualTo(67);
        assertThat(reloaded.getStadiumCapacity()).isEqualTo(41500);
        assertThat(reloaded.getSponsorshipPerRound()).isEqualByComparingTo("536000.00");
        assertThat(reloaded.getStadiumUpkeepPerRound()).isEqualByComparingTo("166000.00");
        assertThat(reloadedPlayer.getWage()).isEqualByComparingTo("84500.00");
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getType()).isEqualTo(FinanceTransactionType.GATE);
        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("1935000.00");

        // adjustBalance mutates by a signed delta
        reloaded.adjustBalance(new BigDecimal("-250000.00"));
        assertThat(reloaded.getBalance()).isEqualByComparingTo("750000.00");
    }
}
