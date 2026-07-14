package br.com.carvoeiromanager.stats;

import java.util.List;

public record PlayerStatsResponse(List<SeasonStatsLine> seasons, SeasonStatsLine totals) {
}
