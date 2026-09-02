package br.com.carvoeiromanager.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SquadRulesTest {

    @Test
    void squadSizeLimitsAreTheOnesEveryDeparturePathCountsAgainst() {
        assertThat(SquadRules.MAX_SQUAD_SIZE).isEqualTo(45);
        assertThat(SquadRules.MIN_SQUAD_SIZE).isEqualTo(14);
    }
}
