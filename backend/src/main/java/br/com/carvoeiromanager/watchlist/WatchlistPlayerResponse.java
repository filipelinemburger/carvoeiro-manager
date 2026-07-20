package br.com.carvoeiromanager.watchlist;

import java.util.UUID;

public record WatchlistPlayerResponse(UUID playerId, String name, String position, int age,
                                      int overall, String clubName, boolean freeAgent,
                                      boolean transferListed, boolean preContractEligible) {}
