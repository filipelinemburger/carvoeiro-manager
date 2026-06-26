package br.com.elifootcarvoeiro.match;

import java.util.List;
import java.util.UUID;

public record SetLineupRequest(String formation, List<UUID> startingPlayerIds) {
}
