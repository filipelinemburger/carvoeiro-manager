package br.com.carvoeiromanager.competition;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One fixture in the managed club's season, oriented from that club's point of view:
 * {@code home} tells whether the managed club plays at home, and goalsFor/goalsAgainst are
 * always relative to the managed club (not literally home/away goals). {@code weather} and
 * {@code temperatureC} are null until the eve of the match (see {@code WeatherService}'s
 * reveal rule); {@code attendance} is null until the fixture has been played.
 */
public record FixtureEntryResponse(
        UUID fixtureId,
        int round,
        LocalDate matchDate,
        boolean home,
        UUID opponentClubId,
        String opponentName,
        String opponentShortName,
        String stadiumName,
        boolean played,
        Integer goalsFor,
        Integer goalsAgainst,
        Integer attendance,
        String weather,
        Integer temperatureC
) {}
