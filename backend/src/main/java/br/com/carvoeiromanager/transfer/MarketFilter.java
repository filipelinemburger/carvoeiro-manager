package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.player.Position;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional narrowing criteria for {@link TransferService#getMarket}. Every field is nullable —
 * an absent field applies no constraint. Min/max pairs are both inclusive.
 */
public record MarketFilter(
        UUID clubId, Position position,
        Integer ageMin, Integer ageMax,
        Integer overallMin, Integer overallMax,
        BigDecimal valueMin, BigDecimal valueMax) {

    private static final MarketFilter EMPTY = new MarketFilter(
            null, null, null, null, null, null, null, null);

    public static MarketFilter empty() {
        return EMPTY;
    }

    boolean matches(MarketPlayerResponse player) {
        if (clubId != null && !clubId.equals(player.clubId())) {
            return false;
        }
        if (position != null && !position.name().equals(player.position())) {
            return false;
        }
        if (ageMin != null && player.age() < ageMin) {
            return false;
        }
        if (ageMax != null && player.age() > ageMax) {
            return false;
        }
        if (overallMin != null && player.overall() < overallMin) {
            return false;
        }
        if (overallMax != null && player.overall() > overallMax) {
            return false;
        }
        if (valueMin != null && player.value().compareTo(valueMin) < 0) {
            return false;
        }
        if (valueMax != null && player.value().compareTo(valueMax) > 0) {
            return false;
        }
        return true;
    }
}
