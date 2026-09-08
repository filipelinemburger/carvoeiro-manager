import type { QueryClient } from '@tanstack/react-query'

/**
 * Every query a completed loan move can make stale — accepting a counter or getting a fresh
 * ACCEPT, from either direction (loan-out on SquadPage, loan-in on TransferMarketPage). Shared by
 * both pages so their invalidation sets can never drift apart again.
 *
 * That drift is exactly what caused a real regression: TransferMarketPage's own invalidator used
 * to invalidate squad/finances/market but not `lineup`, because loan-in used to be the only loan
 * action reachable there and borrowing a player never touches the managed club's saved XI.
 * Mounting `PendingLoanOffers` on TransferMarketPage changed that — accepting a counter there can
 * now also be a loan-*out* completing (propose "Emprestar" on SquadPage, get COUNTERED, close the
 * dialog, accept from the Transfer Market instead), which moves a player OUT of the squad and
 * therefore out of the saved lineup. The server already handles this
 * (`LoanService.execute` -> `SquadDeparture.cleanManagedLineup`), but the client's
 * `['lineup', saveGameId, managedClubId]` cache has `staleTime: Infinity` and is warm from the
 * visit that made the offer — nothing was refetching it. One shared invalidator, covering both
 * pages' loan mutations, means every one of them (loan-out, loan-in, accept-counter from either
 * page) always drags `lineup` along instead of relying on each page to remember to list it.
 */
export function invalidateAfterLoanMove(
  queryClient: QueryClient,
  saveGameId: string,
  managedClubId: string,
) {
  void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
  void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, managedClubId] })
  void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
  void queryClient.invalidateQueries({ queryKey: ['lineup', saveGameId, managedClubId] })
  void queryClient.invalidateQueries({ queryKey: ['loanOffers', saveGameId] })
}
