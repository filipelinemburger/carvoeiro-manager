package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.assertj.core.api.Assertions.assertThat;

class InjurySeverityTest {

    @Test
    void noneHasZeroLayoff() {
        assertThat(InjurySeverity.NONE.layoffMatches(new Random(1))).isZero();
    }

    @Test
    void layoffStaysWithinTierRange() {
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            assertThat(InjurySeverity.KNOCK.layoffMatches(rng)).isEqualTo(1);
            assertThat(InjurySeverity.MINOR.layoffMatches(rng)).isBetween(2, 4);
            assertThat(InjurySeverity.MAJOR.layoffMatches(rng)).isBetween(5, 12);
        }
    }

    @Test
    void rollNeverReturnsNoneAndFollowsDistribution() {
        Random rng = new Random(7);
        int knock = 0, minor = 0, major = 0;
        for (int i = 0; i < 10_000; i++) {
            InjurySeverity s = InjurySeverity.roll(rng);
            assertThat(s).isNotEqualTo(InjurySeverity.NONE);
            switch (s) { case KNOCK -> knock++; case MINOR -> minor++; case MAJOR -> major++; default -> {} }
        }
        assertThat(knock).isGreaterThan(minor);   // ~60% vs ~30%
        assertThat(minor).isGreaterThan(major);   // ~30% vs ~10%
    }
}
