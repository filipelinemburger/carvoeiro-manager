package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PlayerLoanEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanentity_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private UUID anotherClubId(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
    }

    @Test
    void loaningOutRecordsTheOwnerTheTermsAndTheNewClub() {
        Career c = createCareer();
        UUID borrower = anotherClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        p.loanTo(borrower, 40, false);
        playerRepository.save(p);

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isOnLoan()).isTrue();
        assertThat(reloaded.getOwnerClubId()).isEqualTo(c.clubId());
        assertThat(reloaded.getClubId()).isEqualTo(borrower);
        assertThat(reloaded.getLoanWageShare()).isEqualTo(40);
        assertThat(reloaded.getLoanMayFaceOwner()).isFalse();
    }

    @Test
    void returningSendsHimHomeAndClearsEveryLoanColumn() {
        Career c = createCareer();
        UUID borrower = anotherClubId(c);
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        p.loanTo(borrower, 40, false);
        playerRepository.save(p);

        Player onLoan = playerRepository.findById(p.getId()).orElseThrow();
        onLoan.returnFromLoan();
        playerRepository.save(onLoan);

        Player home = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(home.isOnLoan()).isFalse();
        assertThat(home.getClubId()).isEqualTo(c.clubId());
        assertThat(home.getOwnerClubId()).isNull();
        assertThat(home.getLoanWageShare()).isNull();
        assertThat(home.getLoanMayFaceOwner()).isNull();
    }

    @Test
    void aPlayerWhoIsNotOnLoanHasNoLoanState() {
        Career c = createCareer();
        Player p = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();

        assertThat(p.isOnLoan()).isFalse();
        assertThat(p.getOwnerClubId()).isNull();
    }
}
