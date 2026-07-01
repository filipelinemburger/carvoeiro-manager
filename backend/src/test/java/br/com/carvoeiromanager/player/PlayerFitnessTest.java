// PlayerFitnessTest.java
package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerFitnessTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 25, "BRA",
                70, 70, 70, 70, 10, 70, 72);
    }

    @Test
    void newPlayerStartsFullyFitWithNoLastMatchDateAndNoInjury() {
        Player p = player();
        assertThat(p.getFitness()).isEqualTo(100);
        assertThat(p.getLastMatchDate()).isNull();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
    }

    @Test
    void recordAppearanceStoresBaseFitnessAndMatchDate() {
        Player p = player();
        LocalDate d = LocalDate.of(2026, 3, 1);
        p.recordAppearance(63, d);
        assertThat(p.getFitness()).isEqualTo(63);
        assertThat(p.getLastMatchDate()).isEqualTo(d);
    }

    @Test
    void applyInjuryStampsSeverityAndLayoff() {
        Player p = player();
        p.applyInjury(InjurySeverity.MINOR, 3);
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.MINOR);
        assertThat(p.getInjuryMatches()).isEqualTo(3);
        assertThat(p.isEligible()).isFalse();
    }

    @Test
    void tickInjuryClearsSeverityWhenLayoffReachesZero() {
        Player p = player();
        p.applyInjury(InjurySeverity.KNOCK, 1);
        p.tickInjury();
        assertThat(p.getInjuryMatches()).isZero();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
        assertThat(p.isEligible()).isTrue();
    }

    @Test
    void recordAppearanceClampsFitnessToZeroAndHundred() {
        Player low = player();
        low.recordAppearance(-5, LocalDate.of(2026, 3, 1));
        assertThat(low.getFitness()).isEqualTo(0);

        Player high = player();
        high.recordAppearance(110, LocalDate.of(2026, 3, 1));
        assertThat(high.getFitness()).isEqualTo(100);
    }

    @Test
    void tickInjuryKeepsSeverityUntilLayoffFullyElapses() {
        Player p = player();
        p.applyInjury(InjurySeverity.MINOR, 2);
        p.tickInjury();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.MINOR);
        assertThat(p.getInjuryMatches()).isEqualTo(1);
        p.tickInjury();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
        assertThat(p.getInjuryMatches()).isZero();
    }

    @Test
    void tickInjuryOnFitPlayerIsNoOp() {
        Player p = player();
        p.tickInjury();
        assertThat(p.getInjuryMatches()).isZero();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
    }

    @Test
    void resetSeasonStateRestoresFullFitnessAndClearsInjury() {
        Player p = player();
        p.recordAppearance(40, LocalDate.of(2026, 5, 5));
        p.applyInjury(InjurySeverity.MAJOR, 8);
        p.resetSeasonState();
        assertThat(p.getFitness()).isEqualTo(100);
        assertThat(p.getLastMatchDate()).isNull();
        assertThat(p.getInjurySeverity()).isEqualTo(InjurySeverity.NONE);
        assertThat(p.getInjuryMatches()).isZero();
    }
}
