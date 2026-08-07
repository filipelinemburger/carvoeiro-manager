package br.com.carvoeiromanager.competition;

import java.util.UUID;

/**
 * Two fixtures forming one knockout tie. The higher seed hosts the second leg, so
 * {@code secondLeg.getHomeClubId()} is always the higher seed. The aggregate is computed by
 * {@link TieResolver}, never stored.
 */
public record KnockoutTie(UUID tieId, UUID higherSeedClubId, UUID lowerSeedClubId,
                          Fixture firstLeg, Fixture secondLeg) { }
