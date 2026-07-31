package br.com.carvoeiromanager.club;

import java.util.UUID;

/** One club as shown in the club-browsing list: identity, colors, reputation, squad size, and
 * geography. Every club has a city/state/stadium, so {@code stadiumCapacity} is non-nullable;
 * {@code city}/{@code state} are still nullable for an unseeded club. */
public record ClubSummaryResponse(
        UUID id,
        String name,
        String commonName,
        String shortName,
        String primaryColor,
        String secondaryColor,
        int reputation,
        int squadSize,
        String city,
        String state,
        int stadiumCapacity,
        String stadiumName
) {
}
