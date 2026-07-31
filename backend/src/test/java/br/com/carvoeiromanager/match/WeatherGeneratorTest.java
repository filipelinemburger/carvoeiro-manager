package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherGeneratorTest {

    private static final UUID FIXTURE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * A well-spread synthetic fixture id. Sequential UUIDs like {@code new UUID(i, i)} are
     * degenerate for this generator — that one XORs to a constant 0 seed — and small sequential
     * seeds give java.util.Random a badly correlated first draw. Real fixture ids come from
     * UUID.randomUUID(), so tests must model that spread.
     */
    private static UUID fixtureId(long i) {
        return new UUID(i * 0x9E3779B97F4A7C15L, i * 0xC2B2AE3D27D4EB4FL);
    }

    @Test
    void sameFixtureAlwaysYieldsTheSameWeather() {
        LocalDate date = LocalDate.of(2026, 3, 14);
        Weather first = WeatherGenerator.forFixture(FIXTURE, ClimateZone.SUL, date);

        for (int i = 0; i < 1000; i++) {
            assertThat(WeatherGenerator.forFixture(FIXTURE, ClimateZone.SUL, date)).isEqualTo(first);
        }
    }

    @Test
    void differentFixturesDisagreeAtLeastSometimes() {
        LocalDate date = LocalDate.of(2026, 3, 14);
        long distinct = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> fixtureId(i))
                .map(id -> WeatherGenerator.forFixture(id, ClimateZone.SUL, date))
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(5);
    }

    @Test
    void southernWinterIsNeverTropical() {
        LocalDate july = LocalDate.of(2026, 7, 12);
        for (int i = 0; i < 500; i++) {
            Weather weather = WeatherGenerator.forFixture(fixtureId(i), ClimateZone.SUL, july);
            assertThat(weather.temperatureC()).isLessThanOrEqualTo(19);
        }
    }

    @Test
    void northeasternCoastIsNeverCold() {
        for (int month = 1; month <= 12; month++) {
            LocalDate date = LocalDate.of(2026, month, 10);
            for (int i = 0; i < 200; i++) {
                Weather weather = WeatherGenerator.forFixture(
                        fixtureId(i * 100L + month), ClimateZone.NORDESTE_LITORAL, date);
                assertThat(weather.temperatureC()).as("month %d", month).isGreaterThanOrEqualTo(15);
            }
        }
    }

    @Test
    void rainProducesHeavyRainSometimesButNotAlways() {
        LocalDate may = LocalDate.of(2026, 5, 10);
        long heavy = java.util.stream.IntStream.range(0, 2000)
                .mapToObj(i -> fixtureId(i))
                .map(id -> WeatherGenerator.forFixture(id, ClimateZone.NORDESTE_LITORAL, may))
                .filter(w -> w.condition() == WeatherCondition.CHUVA_FORTE)
                .count();
        assertThat(heavy).isBetween(50L, 500L);
    }

    @Test
    void wetnessIsOrdered() {
        assertThat(WeatherCondition.ENSOLARADO.wetness()).isEqualTo(0.0);
        assertThat(WeatherCondition.CHUVA.wetness()).isEqualTo(0.5);
        assertThat(WeatherCondition.CHUVA_FORTE.wetness()).isEqualTo(1.0);
    }
}
