package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PreContractMarketDayIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractService preContractService;
    @Autowired PreContractNegotiation negotiation;
    @Autowired PreContractAgreementRepository agreementRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer(LocalDate date) {
        User user = User.create("mkday_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        save.setCurrentDate(date);
        saveGameRepository.save(save);
        return c;
    }

    private SaveGame save(Career c) {
        return saveGameRepository.findById(c.id()).orElseThrow();
    }

    @Test
    void closedWindowDaysDoNothing() {
        Career c = createCareer(LocalDate.of(2026, 6, 4));
        List<PreContractEventResponse> events =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 6, 4));
        assertThat(events).isEmpty();
        assertThat(agreementRepository.findBySaveGameId(c.id())).isEmpty();
    }

    @Test
    void aiRenewalPassIsIdempotentAcrossStops() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        // Put every AI final-year decision in play: pin ALL rival contracts to 1 year.
        List<Player> all = playerRepository.findBySaveGameId(c.id());
        all.stream().filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .forEach(p -> p.setContractYears(1));
        playerRepository.saveAll(all);

        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 15));
        List<UUID> releasedAfterFirst = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .map(Player::getId).sorted().toList();
        // Some were renewed (2-5 total years now), some released (still 1).
        assertThat(releasedAfterFirst.size()).isLessThan(
                (int) playerRepository.findBySaveGameId(c.id()).stream()
                        .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                        .filter(p -> !c.clubId().equals(p.getClubId())).count());

        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        List<UUID> releasedAfterSecond = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .filter(p -> p.getContractYears() == 1)
                .map(Player::getId).sorted().toList();
        // Idempotent: the second stop releases exactly the same set, renews nobody new.
        assertThat(releasedAfterSecond).isEqualTo(releasedAfterFirst);
    }

    @Test
    void anAgreedOutgoingDealResolvesOnTheNextStop() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        Player rival = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.isYouthProspect() && !p.isFreeAgent())
                .filter(p -> !c.clubId().equals(p.getClubId()))
                .findFirst().orElseThrow();
        rival.setContractYears(1);
        playerRepository.save(rival);
        BigDecimal demand = negotiation.demand(rival.getOverall(), rival.getAge());
        preContractService.makeOffer(c.id(), c.userId(), rival.getId(), demand, 3);

        // Same-day stop must NOT resolve a deal agreed today...
        preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 15));
        assertThat(agreementRepository.findBySaveGameId(c.id()).get(0).getStatus())
                .isEqualTo(PreContractStatus.AGREED);

        // ...the next stop resolves it one way or the other, deterministically.
        List<PreContractEventResponse> events =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        PreContractAgreement resolved = agreementRepository.findBySaveGameId(c.id()).get(0);
        assertThat(resolved.getStatus()).isIn(PreContractStatus.LOCKED, PreContractStatus.CANCELLED);
        if (resolved.getStatus() == PreContractStatus.CANCELLED) {
            // Counter-renew won: the player renewed with his club (current year + 2-4 more).
            Player kept = playerRepository.findById(rival.getId()).orElseThrow();
            assertThat(kept.getContractYears()).isBetween(3, 5);
            assertThat(events).extracting(PreContractEventResponse::kind).contains("CANCELLED_OUT");
        } else {
            assertThat(events).extracting(PreContractEventResponse::kind).contains("LOCKED_OUT");
        }
        // Determinism: replaying the same date yields no further changes (already resolved).
        List<PreContractEventResponse> replay =
                preContractService.processMarketDay(save(c), LocalDate.of(2026, 7, 25));
        assertThat(replay).extracting(PreContractEventResponse::kind)
                .doesNotContain("CANCELLED_OUT", "LOCKED_OUT");
    }

    @Test
    void atMostOnePoachAgreementPerStopAndItTargetsAManagedFinalYearPlayer() {
        Career c = createCareer(LocalDate.of(2026, 7, 15));
        // Make the whole managed squad prime poach bait.
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        squad.forEach(p -> p.setContractYears(1));
        playerRepository.saveAll(squad);

        // Run enough stops, on the real calendar's 10-day TRANSFER cadence, that the odds of
        // never rolling a poach attempt (~50%/stop) are negligible: 0.5^20 ≈ 1e-6. Each stop
        // may still create at most one new incoming agreement.
        int previous = 0;
        LocalDate stop = LocalDate.of(2026, 7, 15);
        for (int i = 0; i < 20; i++) {
            preContractService.processMarketDay(save(c), stop);
            int now = (int) agreementRepository.findBySaveGameId(c.id()).stream()
                    .filter(a -> a.getFromClubId().equals(c.clubId())).count();
            assertThat(now - previous).isBetween(0, 1);
            previous = now;
            stop = stop.plusDays(10);
        }
        // At least one poach must have actually occurred across the 20 stops — otherwise this
        // test would trivially pass without ever exercising poach creation.
        assertThat(agreementRepository.findBySaveGameId(c.id()).stream()
                .filter(a -> a.getFromClubId().equals(c.clubId())).count()).isGreaterThan(0);
        // Every incoming agreement targets a managed final-year senior.
        agreementRepository.findBySaveGameId(c.id()).stream()
                .filter(a -> a.getFromClubId().equals(c.clubId()))
                .forEach(a -> {
                    Player p = playerRepository.findById(a.getPlayerId()).orElseThrow();
                    assertThat(p.getClubId()).isEqualTo(c.clubId());
                    assertThat(p.getAge()).isLessThanOrEqualTo(33);
                });
    }
}
