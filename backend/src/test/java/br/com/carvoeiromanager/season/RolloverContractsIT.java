package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RolloverContractsIT {

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

    /**
     * The probe player the test tracks across the rollover must survive it: rollover ages
     * everyone +1 and then rolls retirement (possible from age 35, certain at 38) — a retired
     * player is DELETED, so findById would throw and the test would flake on whichever player
     * happened to sit first in DB order. Age &lt;= 33 (34 after the tick) has zero retirement
     * probability. Youth prospects follow a separate develop/depart path, so skip them too.
     */
    private static Player probePlayer(List<Player> squad) {
        return squad.stream()
                .filter(p -> !p.isYouthProspect())
                .filter(p -> p.getAge() <= 33)
                .findFirst().orElseThrow();
    }

    private Career playToSeasonEnd() {
        User user = User.create("roll_" + UUID.randomUUID(), "hash");
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
    void aManagedPlayerWhoseContractExpiresBecomesAFreeAgent() {
        Career c = playToSeasonEnd();
        // Force one managed player's contract to expire this rollover; keep the rest safe.
        List<Player> squad = playerRepository.findByClubId(c.clubId());
        squad.forEach(p -> p.setContractYears(5));
        Player expiring = probePlayer(squad);
        expiring.setContractYears(1); // -> 0 after tick -> lapses
        playerRepository.saveAll(squad);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();
        assertThat(reloaded.getContractYears()).isZero();
        assertThat(r.expiredContracts()).extracting(SquadChangeResponse::playerName)
                .contains(expiring.getName());
    }

    @Test
    void anAiPlayerWhoseContractExpiresIsAutoRenewedAndStays() {
        Career c = playToSeasonEnd();
        UUID aiClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        List<Player> aiSquad = playerRepository.findByClubId(aiClub);
        aiSquad.forEach(p -> p.setContractYears(5));
        Player expiring = probePlayer(aiSquad);
        expiring.setContractYears(1);
        playerRepository.saveAll(aiSquad);

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.getClubId()).isEqualTo(aiClub); // stayed
        assertThat(reloaded.getContractYears()).isBetween(2, 4); // auto-renewed
    }

    @Test
    void theFreeAgentPoolIsReplenishedToTheFloor() {
        Career c = playToSeasonEnd();
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(allPlayers);

        rolloverService.rollover(c.id(), c.userId());

        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size())
                .isGreaterThanOrEqualTo(6);
    }
}
