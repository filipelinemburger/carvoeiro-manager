package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The board picks objectives by scope and league targets by tier, so the seed must carry both. */
class SeedScopeTest {

    private static final List<String> TWO = List.of("AAA", "BBB");

    @Test
    void theRealSeedMarksTheCatarinenseAsStateAndEachSerieWithItsTier() {
        Map<String, SeedCompetition> byName = new SeedLoader().loadCareer().competitions().stream()
                .collect(Collectors.toMap(SeedCompetition::name, Function.identity()));

        assertThat(byName.get("Campeonato Catarinense").scopeOrDefault()).isEqualTo("STATE");
        assertThat(byName.get("Campeonato Catarinense").tier()).isNull();
        assertThat(byName.get("Série A").scopeOrDefault()).isEqualTo("NATIONAL");
        assertThat(byName.get("Série A").tier()).isEqualTo(1);
        assertThat(byName.get("Série B").tier()).isEqualTo(2);
        assertThat(byName.get("Série C").tier()).isEqualTo(3);
    }

    @Test
    void everyRealSeedCompetitionPassesTheValidator() {
        for (SeedCompetition c : new SeedLoader().loadCareer().competitions()) {
            assertThatCode(() -> SeedScopeValidator.validate(c)).as(c.name()).doesNotThrowAnyException();
        }
    }

    @Test
    void aNationalCompetitionWithoutATierIsRejected() {
        SeedCompetition noTier = new SeedCompetition("Série D", "LEAGUE", 2026, TWO, null, null, null);
        assertThatThrownBy(() -> SeedScopeValidator.validate(noTier))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Série D")
                .hasMessageContaining("tier");
    }

    @Test
    void aStateCompetitionWithATierIsRejected() {
        SeedCompetition tiered = new SeedCompetition("Paulistão", "LEAGUE", 2026, TWO, null, "STATE", 1);
        assertThatThrownBy(() -> SeedScopeValidator.validate(tiered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Paulistão");
    }

    @Test
    void anUnknownScopeIsRejected() {
        SeedCompetition odd = new SeedCompetition("Copa", "LEAGUE", 2026, TWO, null, "CONTINENTAL", null);
        assertThatThrownBy(() -> SeedScopeValidator.validate(odd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTINENTAL");
    }
}
