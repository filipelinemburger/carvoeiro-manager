package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgeBandTest {

    @Test
    void classifiesAgeIntoBands() {
        assertThat(AgeBand.of(19)).isEqualTo(AgeBand.YOUNG);
        assertThat(AgeBand.of(23)).isEqualTo(AgeBand.YOUNG);
        assertThat(AgeBand.of(24)).isEqualTo(AgeBand.PRIME);
        assertThat(AgeBand.of(29)).isEqualTo(AgeBand.PRIME);
        assertThat(AgeBand.of(30)).isEqualTo(AgeBand.EXPERIENCED);
        assertThat(AgeBand.of(32)).isEqualTo(AgeBand.EXPERIENCED);
        assertThat(AgeBand.of(33)).isEqualTo(AgeBand.VETERAN);
        assertThat(AgeBand.of(39)).isEqualTo(AgeBand.VETERAN);
    }

    @Test
    void veteransRecoverSlowerAndAreMoreInjuryProneThanYouth() {
        assertThat(AgeBand.VETERAN.recoveryFactor()).isLessThan(AgeBand.YOUNG.recoveryFactor());
        assertThat(AgeBand.VETERAN.injuryFactor()).isGreaterThan(AgeBand.YOUNG.injuryFactor());
        assertThat(AgeBand.VETERAN.decayFactor()).isGreaterThan(AgeBand.YOUNG.decayFactor());
    }

    @Test
    void primeIsTheNeutralBaseline() {
        assertThat(AgeBand.PRIME.recoveryFactor()).isEqualTo(1.0);
        assertThat(AgeBand.PRIME.injuryFactor()).isEqualTo(1.0);
        assertThat(AgeBand.PRIME.decayFactor()).isEqualTo(1.0);
    }
}
