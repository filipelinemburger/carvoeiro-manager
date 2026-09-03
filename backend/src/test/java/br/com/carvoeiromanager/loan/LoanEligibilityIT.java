package br.com.carvoeiromanager.loan;

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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanEligibilityIT {

    private final LoanEligibility eligibility = new LoanEligibility();

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("loanelig_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private Player aManagedPlayer(Career c) {
        return playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
    }

    private List<UUID> twoOtherClubs(Career c) {
        return playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .distinct().sorted().limit(2).toList();
    }

    @Test
    void aPlayerWhoIsNotOnLoanMayFaceAnyone() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);

        assertThat(eligibility.mayFace(p, others.get(0))).isTrue();
        assertThat(eligibility.mayFace(p, c.clubId())).isTrue();
    }

    @Test
    void aBorrowedPlayerMayFaceEveryClubExceptHisOwner() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);
        UUID owner = c.clubId();
        p.loanTo(others.get(0), 50, false);   // release withheld

        assertThat(eligibility.mayFace(p, others.get(1))).isTrue();
        assertThat(eligibility.mayFace(p, owner)).isFalse();
    }

    @Test
    void theNegotiatedReleaseLetsHimFaceHisOwner() {
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        List<UUID> others = twoOtherClubs(c);
        UUID owner = c.clubId();
        p.loanTo(others.get(0), 50, true);    // release granted

        assertThat(eligibility.mayFace(p, owner)).isTrue();
    }

    @Test
    void aNullOpponentImposesNoRestriction() {
        // The two-argument bestXi/startingXi overloads pass null; that must never bar anyone.
        Career c = createCareer();
        Player p = aManagedPlayer(c);
        p.loanTo(twoOtherClubs(c).get(0), 50, false);

        assertThat(eligibility.mayFace(p, null)).isTrue();
    }
}
