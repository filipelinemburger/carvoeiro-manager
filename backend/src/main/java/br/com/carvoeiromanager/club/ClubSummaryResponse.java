package br.com.carvoeiromanager.club;

import java.util.UUID;

/** One club as shown in the club-browsing list: identity, colors, reputation, and squad size. */
public record ClubSummaryResponse(
        UUID id,
        String name,
        String commonName,
        String shortName,
        String primaryColor,
        String secondaryColor,
        int reputation,
        int squadSize
) {
}
