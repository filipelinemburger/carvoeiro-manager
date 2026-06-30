package br.com.carvoeiromanager.match;

import java.util.List;
import java.util.UUID;

public record LineupResponse(
        UUID lineupId,
        UUID clubId,
        String formation,
        List<LineupStarter> starters
) {
}
