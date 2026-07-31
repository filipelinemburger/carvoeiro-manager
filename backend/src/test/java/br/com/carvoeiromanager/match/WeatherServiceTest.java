package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.competition.Fixture;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherServiceTest {

    private final WeatherService service = new WeatherService();

    private static Club homeClub() {
        Club club = Club.create(UUID.randomUUID(), "Criciúma Esporte Clube", "CRI");
        club.setClimateZone(ClimateZone.SUL);
        return club;
    }

    @Test
    void weatherIsHiddenMoreThanADayOut() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 18)))
                .isEmpty();
    }

    @Test
    void weatherIsVisibleOnTheEve() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 19)))
                .isPresent();
    }

    @Test
    void weatherIsVisibleOnMatchDayAndAfter() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 3, 20)))
                .isPresent();
        assertThat(service.visibleWeather(fixture, homeClub(), LocalDate.of(2026, 4, 30)))
                .isPresent();
    }

    @Test
    void theRevealRuleNeverChangesTheWeatherItself() {
        Fixture fixture = fixtureOn(LocalDate.of(2026, 3, 20));
        Club club = homeClub();
        Optional<Weather> revealed = service.visibleWeather(fixture, club, LocalDate.of(2026, 3, 19));
        assertThat(revealed).contains(service.weatherFor(fixture, club));
    }

    private static Fixture fixtureOn(LocalDate date) {
        return Fixture.create(UUID.randomUUID(), UUID.randomUUID(), 1,
                UUID.randomUUID(), UUID.randomUUID(), date);
    }
}
