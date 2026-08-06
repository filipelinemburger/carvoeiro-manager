package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.contract.PreContractAgreement;
import br.com.carvoeiromanager.contract.PreContractAgreementRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Autowired PreContractAgreementRepository preContractAgreementRepository;

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
     *
     * <p>Two further guards, added after the 2026 Catarinense seed expansion broke this test:
     * <ul>
     *   <li>A player holding a pre-contract agreement does NOT lapse into free agency at
     *       rollover — {@link SeasonRolloverService} moves him to his new club instead, leaving
     *       {@code isFreeAgent()} false. Skip those.
     *   <li>{@code findByClubId} has no ORDER BY, so "first in DB order" changes whenever the
     *       seed does. Pick the lowest id instead, which is stable across seed changes.
     * </ul>
     */
    private Player probePlayer(UUID saveGameId, List<Player> squad) {
        Set<UUID> preContracted = preContractAgreementRepository.findBySaveGameId(saveGameId).stream()
                .map(PreContractAgreement::getPlayerId)
                .collect(Collectors.toSet());
        return squad.stream()
                .filter(p -> !p.isYouthProspect())
                .filter(p -> p.getAge() <= 33)
                .filter(p -> !preContracted.contains(p.getId()))
                .min(Comparator.comparing(Player::getId))
                .orElseThrow();
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
        Player expiring = probePlayer(c.id(), squad);
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
    void anUnrenewedAiPlayerWhoseContractExpiresLapsesIntoTheFreeAgentPool() {
        Career c = playToSeasonEnd();
        UUID aiClub = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId).filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        List<Player> aiSquad = playerRepository.findByClubId(aiClub);
        aiSquad.forEach(p -> p.setContractYears(5));
        Player expiring = probePlayer(c.id(), aiSquad);
        expiring.setContractYears(1); // released by his club, unsigned by anyone
        playerRepository.saveAll(aiSquad);

        rolloverService.rollover(c.id(), c.userId());

        Player reloaded = playerRepository.findById(expiring.getId()).orElseThrow();
        assertThat(reloaded.isFreeAgent()).isTrue();   // organic inflow into the real pool
        assertThat(reloaded.getContractYears()).isZero();
    }

    @Test
    void theFreeAgentPoolIsNotReplenishedAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5)); // nobody's contract expires this rollover
        playerRepository.saveAll(allPlayers);
        int poolBefore = playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size();

        rolloverService.rollover(c.id(), c.userId());

        // No contracts expired, so the pool can only shrink (a free agent retiring)
        // or stay the same — it must never be topped back up by generation.
        assertThat(playerRepository.findBySaveGameIdAndClubIdIsNull(c.id()).size())
                .isLessThanOrEqualTo(poolBefore);
    }
}
