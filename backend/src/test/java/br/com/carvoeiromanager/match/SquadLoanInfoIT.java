package br.com.carvoeiromanager.match;

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

import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SquadLoanInfoIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired LineupService lineupService;
    @Autowired PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;

    private UUID aRivalClubId(UUID saveGameId, UUID managedClubId) {
        return playerRepository.findBySaveGameId(saveGameId).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(managedClubId))
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    @Test
    void aLoanedPlayerCarriesHisOwnersClubNameInTheBorrowersSquad() {
        User user = User.create("loaninfo_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        UUID borrowerClubId = aRivalClubId(save.id(), save.managedClubId());
        Player lent = playerRepository.findByClubIdAndYouthProspectFalse(save.managedClubId()).stream()
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        lent.loanTo(borrowerClubId, 40, false);
        playerRepository.save(lent);

        Club owner = clubRepository.findById(save.managedClubId()).orElseThrow();

        SquadResponse borrowerSquad = lineupService.getSquad(save.id(), borrowerClubId, user.getId());

        PlayerResponse loanedResponse = borrowerSquad.players().stream()
                .filter(p -> p.id().equals(lent.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(loanedResponse.onLoanFromClubName()).isEqualTo(owner.getName());
    }

    @Test
    void aPlayerNotOnLoanHasNoOwnerClubName() {
        User user = User.create("loaninfo_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        SquadResponse squad = lineupService.getSquad(save.id(), save.managedClubId(), user.getId());

        assertThat(squad.players()).isNotEmpty();
        assertThat(squad.players()).allSatisfy(p ->
                assertThat(p.onLoanFromClubName()).isNull());
    }
}
