package br.com.elifootcarvoeiro.match;

import java.util.List;
import java.util.UUID;

public record LineupResponse(
        UUID lineupId,
        UUID clubId,
        String formation,
        List<UUID> startingPlayerIds
) {
}
