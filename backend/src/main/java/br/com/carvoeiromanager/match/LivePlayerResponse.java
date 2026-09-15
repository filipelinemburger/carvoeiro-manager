package br.com.carvoeiromanager.match;

import java.util.List;

public record LivePlayerResponse(
        String playerId,
        String name,
        String position,
        int overall,
        int fitness,
        boolean hobbled,
        boolean onPitch,
        boolean used,
        boolean eligible,
        String role,
        /** Slot index into the side's displayed formation; null on the bench or when not knowable. */
        Integer slotIndex,
        String detailedPosition,
        List<String> altPositions) {
}
