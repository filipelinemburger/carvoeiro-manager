package br.com.carvoeiromanager.match;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PlayerResponse(
        UUID id,
        String name,
        String position,
        String detailedPosition,
        String detailedPositionLabel,
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
        String focus,
        List<String> altPositions,
        List<String> familiarRoles,
        int contractYears,
        BigDecimal value,
        BigDecimal wageDemand,
        BigDecimal terminationFee,
        int fitness,
        String injurySeverity,
        List<AttributeView> attributes,
        boolean transferListed,
        String listedReason,
        String trainingFocus,
        String trainingIntensity
) {
}
