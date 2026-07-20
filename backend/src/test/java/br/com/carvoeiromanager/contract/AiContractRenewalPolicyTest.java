package br.com.carvoeiromanager.contract;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class AiContractRenewalPolicyTest {

    private final AiContractRenewalPolicy policy = new AiContractRenewalPolicy();

    @Test
    void primeAgePlayerAtSquadStandardIsKept() {
        assertThat(policy.shouldRelease(28, 60, 60, 25)).isFalse();
    }

    @Test
    void oldPlayerIsReleasedWhenTheSquadCanAffordIt() {
        assertThat(policy.shouldRelease(34, 70, 60, 25)).isTrue();
    }

    @Test
    void weakPlayerBelowMedianMinusToleranceIsReleased() {
        assertThat(policy.shouldRelease(25, 54, 60, 25)).isTrue();   // 54 < 60 - 5
        assertThat(policy.shouldRelease(25, 55, 60, 25)).isFalse();  // 55 == 60 - 5, kept
    }

    @Test
    void nobodyIsReleasedWhenTheSquadWouldDropBelowTheFloor() {
        assertThat(policy.shouldRelease(36, 40, 60, 19)).isFalse();
        assertThat(policy.shouldRelease(36, 40, 60, 20)).isTrue();
    }

    @Test
    void renewYearsAreBetweenTwoAndFour() {
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            assertThat(policy.renewYears(rng)).isBetween(2, 4);
        }
    }
}
