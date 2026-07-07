package br.com.carvoeiromanager.match;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PlayerResponse(
        UUID id,
        String name,
        String position,
        int age,
        int overall,
        int pace,
        int finishing,
        int passing,
        int tackling,
        int goalkeeping,
        int stamina,
        int form,
        int morale,
        int yellowCards,
        int suspensionRemaining,
        int injuryMatches,
        boolean eligible,
        String role,
        int contractYears,
        BigDecimal wageDemand,
        int fitness,
        String injurySeverity,
        List<AttributeView> attributes
) {
}
