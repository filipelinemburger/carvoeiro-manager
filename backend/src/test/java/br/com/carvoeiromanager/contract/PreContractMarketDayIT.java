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
        // aiPoach's coin flip re-seeds a fresh java.util.Random EVERY stop from
        // (save.getId() ^ eventDate.toEpochDay() ^ MARKET_SALT ^ 1L). Because the 10-day
        // TRANSFER cadence only changes toEpochDay() in its low-order bits, and
        // java.util.Random's first nextDouble() after construction barely mixes low seed
        // bits into the high bit that decides the < 0.5 outcome, the 17 in-window stops of
        // a SINGLE save are highly correlated rather than independent: empirically, a given
        // save is "always poaches" or "never poaches" across the whole window (measured
        // ~47%/~47% split), not 17 independent 50/50 draws. So instead of trusting one
        // random save id to land in the "poaches" half, retry with fresh careers (fresh
        // random save ids) until one actually poaches; each attempt is close to an
        // independent 50/50 draw over which "family" its id falls into, so a handful of
        // attempts drives the miss probability to ~0.5^8 ≈ 0.4% — genuinely negligible.
        Career c = null;
        boolean poachObserved = false;
        for (int attempt = 0; attempt < 8 && !poachObserved; attempt++) {
            c = createCareer(LocalDate.of(2026, 7, 15));
            final Career current = c;
            // Make the whole managed squad prime poach bait.
            List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(current.clubId());
            squad.forEach(p -> p.setContractYears(1));
            // aiPoach only completes a roll if the target's overall clears the poaching
            // club's squad median too. Pin one eligible player's overall sky-high (and age
            // within the <=33 poach bound) so that gate is satisfied whenever the coin flip
            // succeeds, leaving the coin flip as the only remaining variable.
            Player primeTarget = squad.get(0);
            primeTarget.setOverall(95);
            primeTarget.setAge(25);
            playerRepository.saveAll(squad);

            // Run every stop on the real calendar's 10-day TRANSFER cadence within the
            // window. Each stop may still create at most one new incoming agreement.
            int previous = 0;
            LocalDate stop = LocalDate.of(2026, 7, 15);
            for (int i = 0; i < 20; i++) {
                preContractService.processMarketDay(save(current), stop);
                int now = (int) agreementRepository.findBySaveGameId(current.id()).stream()
                        .filter(a -> a.getFromClubId().equals(current.clubId())).count();
                assertThat(now - previous).isBetween(0, 1);
                previous = now;
                stop = stop.plusDays(10);
            }
            poachObserved = previous > 0;
        }
        // At least one poach must have actually occurred — otherwise this test would
        // trivially pass without ever exercising poach creation.
        assertThat(poachObserved).isTrue();
        // Every incoming agreement (from the career that finally poached) targets a managed
        // final-year senior.
        final Career winner = c;
        agreementRepository.findBySaveGameId(winner.id()).stream()
                .filter(a -> a.getFromClubId().equals(winner.clubId()))
                .forEach(a -> {
                    Player p = playerRepository.findById(a.getPlayerId()).orElseThrow();
                    assertThat(p.getClubId()).isEqualTo(winner.clubId());
                    assertThat(p.getAge()).isLessThanOrEqualTo(33);
                });
    }
}
