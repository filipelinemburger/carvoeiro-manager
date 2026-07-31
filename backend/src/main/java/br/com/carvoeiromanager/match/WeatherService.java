package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.competition.Fixture;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The single place the forecast-reveal rule lives: weather is public knowledge from the eve of
 * the match onward, and hidden before that so it cannot be scouted a month ahead.
 *
 * <p>Deliberately <b>not</b> gated on having an Auxiliar Técnico hired — a staff-less club would
 * otherwise see no forecast anywhere, since no auxiliar means no MATCH_PREVIEW news at all. The
 * auxiliar's value-add is the interpretation, not the forecast.
 *
 * <p>This is a presentation concern only. The engines call {@link #weatherFor} (or
 * {@link WeatherGenerator} directly) and are never affected by the reveal rule.
 */
@Service
public class WeatherService {

    /** Days before kickoff at which the forecast becomes public. */
    private static final long REVEAL_DAYS = 1;

    /** The fixture's weather, ungated. For the engines and for already-played fixtures. */
    public Weather weatherFor(Fixture fixture, Club homeClub) {
        return WeatherGenerator.forFixture(
                fixture.getId(), homeClub.getClimateZone(), fixture.getMatchDate());
    }

    /** The fixture's weather if the manager is allowed to see it yet, else empty. */
    public Optional<Weather> visibleWeather(Fixture fixture, Club homeClub, LocalDate today) {
        if (fixture.getMatchDate() == null || today == null) {
            return Optional.empty();
        }
        long daysOut = ChronoUnit.DAYS.between(today, fixture.getMatchDate());
        return daysOut > REVEAL_DAYS ? Optional.empty() : Optional.of(weatherFor(fixture, homeClub));
    }
}
