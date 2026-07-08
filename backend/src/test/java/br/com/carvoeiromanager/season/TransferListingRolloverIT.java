package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Season rollover must clear both the transfer-listing flag/reason and the appearance tally. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferListingRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("listroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    @Test
    void rolloverClearsListingAndAppearancesForACarriedOverPlayer() {
        Career c = playToSeasonEnd();
        // A young player never retires (0% retirement probability below age 35), so it's
        // guaranteed to carry over into the next season rather than being deleted.
        Player p = playerRepository.findByClubId(c.clubId()).stream()
                .filter(pl -> pl.getAge() < 30)
                .findFirst().orElseThrow();
        int appearancesBeforeBump = p.getSeasonAppearances(); // already accrued during the played season
        p.listForTransfer(ListedReason.OWNER);
        p.recordAppearance(80, LocalDate.now());
        p.recordAppearance(75, LocalDate.now().plusDays(7));
        playerRepository.save(p);
        assertThat(p.isTransferListed()).isTrue();
        assertThat(p.getSeasonAppearances()).isEqualTo(appearancesBeforeBump + 2);
        assertThat(p.getSeasonAppearances()).isGreaterThan(0); // non-vacuous: there's something to clear

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.isTransferListed()).isFalse();
        assertThat(reloaded.getListedReason()).isNull();
        assertThat(reloaded.getSeasonAppearances()).isZero();
    }
}
