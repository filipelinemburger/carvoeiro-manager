package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;

import java.util.UUID;

/** An injury resolved during the match; its layoff is applied post-match at finish. */
public record TimedInjury(UUID playerId, boolean home, int minute, InjurySeverity severity, int layoffMatches) {
}
