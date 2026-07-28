package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.contract.PreContractAgreement;
import br.com.carvoeiromanager.contract.PreContractAgreementRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career playToSeasonEnd() {
        User user = User.create("pcroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    /** Age <= 33 so the probe survives rollover (retirement starts at 35). */
    private Player safeProbe(List<Player> squad) {
        return squad.stream()
                .filter(p -> !p.isYouthProspect())
                .filter(p -> p.getAge() <= 33)
                .findFirst().orElseThrow();
    }

    @Test
    void aLockedIncomingSigningExecutesAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player target = safeProbe(all.stream()
                .filter(p -> !p.isFreeAgent() && !c.clubId().equals(p.getClubId())).toList());
        target.setContractYears(1);
        playerRepository.saveAll(all);
        PreContractAgreement deal = PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), c.clubId(), new BigDecimal("12345.00"), 4,
                LocalDate.of(2026, 8, 1));
        deal.lock();
        agreementRepository.save(deal);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player signed = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(signed.getClubId()).isEqualTo(c.clubId());
        assertThat(signed.getWage()).isEqualByComparingTo(new BigDecimal("12345.00"));
        assertThat(signed.getContractYears()).isEqualTo(4);
        assertThat(r.preContractArrivals()).extracting(SquadChangeResponse::playerName)
                .contains(target.getName());
        // Season-scoped rows are purged.
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }

    @Test
    void aStillAgreedIncomingThreatLocksAndExecutesAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        // The AI poaches managed-club players throughout the pre-contract window, so the probe must
        // be someone it did NOT already sign — otherwise the deal under test is not the one that
        // executes and the test would be asserting the wrong agreement.
        Set<UUID> alreadyDealt = agreementRepository.findBySaveGameId(c.id()).stream()
                .map(PreContractAgreement::getPlayerId).collect(Collectors.toSet());
        Player mine = safeProbe(all.stream()
                .filter(p -> c.clubId().equals(p.getClubId()))
                .filter(p -> !alreadyDealt.contains(p.getId())).toList());
        mine.setContractYears(1);
        playerRepository.saveAll(all);
        UUID poacher = all.stream().map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        agreementRepository.save(PreContractAgreement.create(c.id(), mine.getId(),
                c.clubId(), poacher, new BigDecimal("9999.00"), 2, LocalDate.of(2026, 10, 1)));

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player gone = playerRepository.findById(mine.getId()).orElseThrow();
        assertThat(gone.getClubId()).isEqualTo(poacher);   // incoming threats lock at rollover
        assertThat(r.preContractDepartures()).extracting(SquadChangeResponse::playerName)
                .contains(mine.getName());
    }

    @Test
    void twoLockedDealsOnTheSamePlayerResolveToOneInsteadOfCrashingTheRollover() {
        // GIVEN a player carrying TWO locked pre-contracts. The schema permits it (V30 indexes
        // player_id but does not make it unique), so rollover must resolve it rather than blow up.
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player target = safeProbe(all.stream()
                .filter(p -> !p.isFreeAgent() && !c.clubId().equals(p.getClubId())).toList());
        target.setContractYears(1);
        playerRepository.saveAll(all);
        UUID rival = all.stream().map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()) && !id.equals(target.getClubId()))
                .findFirst().orElseThrow();
        PreContractAgreement toManaged = PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), c.clubId(), new BigDecimal("1000.00"), 3, LocalDate.of(2026, 8, 1));
        toManaged.lock();
        PreContractAgreement toRival = PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), rival, new BigDecimal("2000.00"), 2, LocalDate.of(2026, 8, 1));
        toRival.lock();
        agreementRepository.saveAll(List.of(toManaged, toRival));
        // Only one deal can execute; the winner is the lowest-id agreement, the same deterministic
        // order resolveRemainingAgreements already processes agreements in.
        PreContractAgreement winner = toManaged.getId().compareTo(toRival.getId()) < 0 ? toManaged : toRival;

        rolloverService.rollover(c.id(), c.userId());

        Player moved = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(moved.getClubId()).isEqualTo(winner.getToClubId());
        assertThat(moved.getContractYears()).isEqualTo(winner.getContractYears());
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }

    @Test
    void aStillAgreedOutgoingDealResolvesByTheRollAtRollover() {
        Career c = playToSeasonEnd();
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.forEach(p -> p.setContractYears(5));
        Player target = safeProbe(all.stream()
                .filter(p -> !p.isFreeAgent() && !c.clubId().equals(p.getClubId())).toList());
        target.setContractYears(1);
        playerRepository.saveAll(all);
        agreementRepository.save(PreContractAgreement.create(c.id(), target.getId(),
                target.getClubId(), c.clubId(), new BigDecimal("5000.00"), 3,
                LocalDate.of(2026, 10, 1)));
        UUID originalClub = target.getClubId();

        rolloverService.rollover(c.id(), c.userId());

        // Deterministic either way: he either joined the manager (deal locked) or renewed
        // with his club (counter-renew won) — never lapsed into the pool.
        Player resolved = playerRepository.findById(target.getId()).orElseThrow();
        assertThat(resolved.getClubId()).isIn(c.clubId(), originalClub);
        assertThat(resolved.getClubId()).isNotNull();
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }
}
