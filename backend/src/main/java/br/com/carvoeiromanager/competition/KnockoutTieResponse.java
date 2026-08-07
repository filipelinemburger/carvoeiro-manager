package br.com.carvoeiromanager.competition;

/**
 * One knockout tie. Goal fields are null until the corresponding leg is played;
 * {@code winnerClubId} is null until the tie is decided (either leg unplayed, or level with no
 * shootout recorded yet) — this endpoint is called mid-season when half the bracket may still be
 * unplayed, so it never throws for an undecided tie.
 */
public record KnockoutTieResponse(
        String tieId,
        int tieOrder,
        String higherSeedClubId,
        String higherSeedClubName,
        String lowerSeedClubId,
        String lowerSeedClubName,
        Integer firstLegHomeGoals,
        Integer firstLegAwayGoals,
        Integer secondLegHomeGoals,
        Integer secondLegAwayGoals,
        Integer penaltyHome,
        Integer penaltyAway,
        String winnerClubId
) {}
