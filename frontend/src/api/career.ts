import { apiGet, apiPost, apiPut, apiDelete } from './client'
import type {
  SaveGameResponse,
  SaveGameSummary,
  CreateSaveGameRequest,
  SquadResponse,
  ClubSummary,
  SetLineupRequest,
  LineupResponse,
  AdvanceResponse,
  AdvancePreviewResponse,
  StandingRow,
  FixtureEntry,
  TacticsRequest,
  TacticsResponse,
  TopScorerResponse,
  RolloverResponse,
  SeasonSummary,
  SeasonDetail,
  FinancesResponse,
  MarketPlayer,
  MarketFilters,
  PageResponse,
  TransferOffer,
  TransferListing,
  MakeOfferRequest,
  RespondOfferRequest,
  FreeAgent,
  RenewContractRequest,
  SignFreeAgentRequest,
  PlayerContract,
  TerminationResult,
  PlayerResponse,
  SetTrainingRequest,
  StaffRole,
  StaffRoster,
  StaffCandidate,
  Phase,
  KnockoutTie,
} from './types'

export function createCareer(req: CreateSaveGameRequest, token: string): Promise<SaveGameResponse> {
  return apiPost<SaveGameResponse>('/save-games', req, token)
}

export function getSaveGame(id: string, token: string): Promise<SaveGameResponse> {
  return apiGet<SaveGameResponse>(`/save-games/${id}`, token)
}

// The logged-in user's careers, newest first — powers the "Carregar jogo salvo" dialog.
export function listCareers(token: string): Promise<SaveGameSummary[]> {
  return apiGet<SaveGameSummary[]>('/save-games', token)
}

export function deleteCareer(id: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${id}`, token)
}

export function getSquad(saveGameId: string, clubId: string, token: string): Promise<SquadResponse> {
  return apiGet<SquadResponse>(`/save-games/${saveGameId}/clubs/${clubId}/players`, token)
}

// Full details for any player in the save — a club's squad member or a free agent — used to
// open the details dialog from contexts that only have a bare player id (market, free agents).
export function getPlayer(saveGameId: string, playerId: string, token: string): Promise<PlayerResponse> {
  return apiGet<PlayerResponse>(`/save-games/${saveGameId}/players/${playerId}`, token)
}

export function setTraining(
  saveGameId: string,
  clubId: string,
  req: SetTrainingRequest,
  token: string,
): Promise<SquadResponse> {
  return apiPut<SquadResponse>(`/save-games/${saveGameId}/clubs/${clubId}/training`, req, token)
}

// Every club of the save game, sorted by reputation (highest first) — powers "Clubes".
export function getClubs(saveGameId: string, token: string): Promise<ClubSummary[]> {
  return apiGet<ClubSummary[]>(`/save-games/${saveGameId}/clubs`, token)
}

export function setLineup(
  saveGameId: string,
  clubId: string,
  req: SetLineupRequest,
  token: string,
): Promise<LineupResponse> {
  return apiPut<LineupResponse>(`/save-games/${saveGameId}/clubs/${clubId}/lineup`, req, token)
}

// Returns the saved lineup to rehydrate the squad screen, or null when none has been
// saved yet (the endpoint replies 204, which apiGet resolves to undefined).
export function getLineup(
  saveGameId: string,
  clubId: string,
  token: string,
): Promise<LineupResponse | null> {
  return apiGet<LineupResponse | undefined>(`/save-games/${saveGameId}/clubs/${clubId}/lineup`, token)
    .then(r => r ?? null)
}

export function advanceRound(saveGameId: string, token: string): Promise<AdvanceResponse> {
  return apiPost<AdvanceResponse>(`/save-games/${saveGameId}/advance`, {}, token)
}

// Look-ahead at the next advance so the UI can disable "Avançar" before the click.
export function getAdvancePreview(saveGameId: string, token: string): Promise<AdvancePreviewResponse> {
  return apiGet<AdvancePreviewResponse>(`/save-games/${saveGameId}/advance-preview`, token)
}

// phaseId is optional — omitted, it returns the whole-competition table; the backend ignores
// it for a competition without phases, which is how a phase-less competition (e.g. Série B)
// keeps working unchanged.
export function getStandings(
  saveGameId: string,
  competitionId: string,
  token: string,
  phaseId?: string,
): Promise<StandingRow[]> {
  const query = phaseId ? `?phaseId=${phaseId}` : ''
  return apiGet<StandingRow[]>(
    `/save-games/${saveGameId}/competitions/${competitionId}/standings${query}`,
    token,
  )
}

// The competition's phases, in bracket order — empty for a competition with no configured
// format (e.g. Série B), which is the signal the UI uses to skip the phase selector entirely.
export function getPhases(saveGameId: string, competitionId: string, token: string): Promise<Phase[]> {
  return apiGet<Phase[]>(`/save-games/${saveGameId}/competitions/${competitionId}/phases`, token)
}

// One knockout phase's ties, seeded bracket order. A tie not yet fully played comes back with
// null goal fields and a null winnerClubId rather than an error.
export function getTies(
  saveGameId: string,
  competitionId: string,
  phaseId: string,
  token: string,
): Promise<KnockoutTie[]> {
  return apiGet<KnockoutTie[]>(
    `/save-games/${saveGameId}/competitions/${competitionId}/ties?phaseId=${phaseId}`,
    token,
  )
}

// The managed club's fixtures for a competition, ordered by round — powers the Calendário tab.
export function getFixtures(
  saveGameId: string,
  competitionId: string,
  token: string,
): Promise<FixtureEntry[]> {
  return apiGet<FixtureEntry[]>(
    `/save-games/${saveGameId}/fixtures?competitionId=${competitionId}`,
    token,
  )
}

export function getTactics(
  saveGameId: string,
  clubId: string,
  token: string,
): Promise<TacticsResponse> {
  return apiGet<TacticsResponse>(`/save-games/${saveGameId}/clubs/${clubId}/tactics`, token)
}

export function setTactics(
  saveGameId: string,
  clubId: string,
  req: TacticsRequest,
  token: string,
): Promise<TacticsResponse> {
  return apiPut<TacticsResponse>(`/save-games/${saveGameId}/clubs/${clubId}/tactics`, req, token)
}

export function getTopScorers(
  saveGameId: string,
  competitionId: string,
  token: string,
): Promise<TopScorerResponse[]> {
  return apiGet<TopScorerResponse[]>(
    `/save-games/${saveGameId}/competitions/${competitionId}/top-scorers`,
    token,
  )
}

export function rolloverSeason(saveGameId: string, token: string): Promise<RolloverResponse> {
  return apiPost<RolloverResponse>(`/save-games/${saveGameId}/seasons/rollover`, {}, token)
}

export function getSeasonHistory(saveGameId: string, token: string): Promise<SeasonSummary[]> {
  return apiGet<SeasonSummary[]>(`/save-games/${saveGameId}/seasons`, token)
}

export function getSeason(
  saveGameId: string,
  seasonYear: number,
  token: string,
): Promise<SeasonDetail> {
  return apiGet<SeasonDetail>(`/save-games/${saveGameId}/seasons/${seasonYear}`, token)
}

export function getFinances(
  saveGameId: string,
  clubId: string,
  token: string,
): Promise<FinancesResponse> {
  return apiGet<FinancesResponse>(`/save-games/${saveGameId}/clubs/${clubId}/finances`, token)
}

// Paginated, filterable market listing — page is 1-based; filter fields are omitted from the
// query string when unset so the backend applies no constraint for them.
export function getMarket(
  saveGameId: string,
  filters: MarketFilters,
  page: number,
  size: number,
  token: string,
): Promise<PageResponse<MarketPlayer>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (filters.clubId) params.set('clubId', filters.clubId)
  if (filters.position) params.set('position', filters.position)
  if (filters.ageMin != null) params.set('ageMin', String(filters.ageMin))
  if (filters.ageMax != null) params.set('ageMax', String(filters.ageMax))
  if (filters.overallMin != null) params.set('overallMin', String(filters.overallMin))
  if (filters.overallMax != null) params.set('overallMax', String(filters.overallMax))
  if (filters.valueMin != null) params.set('valueMin', String(filters.valueMin))
  if (filters.valueMax != null) params.set('valueMax', String(filters.valueMax))
  return apiGet<PageResponse<MarketPlayer>>(`/save-games/${saveGameId}/transfers/market?${params.toString()}`, token)
}

export function getOffers(saveGameId: string, token: string): Promise<TransferOffer[]> {
  return apiGet<TransferOffer[]>(`/save-games/${saveGameId}/transfers/offers`, token)
}

export function makeOffer(
  saveGameId: string,
  req: MakeOfferRequest,
  token: string,
): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers`, req, token)
}

export function acceptCounter(saveGameId: string, offerId: string, token: string): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/accept-counter`, {}, token)
}

export function withdrawOffer(saveGameId: string, offerId: string, token: string): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/withdraw`, {}, token)
}

export function respondToOffer(
  saveGameId: string,
  offerId: string,
  req: RespondOfferRequest,
  token: string,
): Promise<TransferOffer> {
  return apiPost<TransferOffer>(`/save-games/${saveGameId}/transfers/offers/${offerId}/respond`, req, token)
}

export function listPlayer(saveGameId: string, playerId: string, token: string): Promise<TransferListing> {
  return apiPut<TransferListing>(`/save-games/${saveGameId}/transfers/listings/${playerId}`, {}, token)
}

export function unlistPlayer(saveGameId: string, playerId: string, token: string): Promise<TransferListing> {
  return apiDelete<TransferListing>(`/save-games/${saveGameId}/transfers/listings/${playerId}`, token)
}

export function getFreeAgents(saveGameId: string, token: string): Promise<FreeAgent[]> {
  return apiGet<FreeAgent[]>(`/save-games/${saveGameId}/free-agents`, token)
}

export function signFreeAgent(
  saveGameId: string,
  playerId: string,
  req: SignFreeAgentRequest,
  token: string,
): Promise<PlayerContract> {
  return apiPost<PlayerContract>(`/save-games/${saveGameId}/free-agents/${playerId}/sign`, req, token)
}

export function renewContract(
  saveGameId: string,
  playerId: string,
  req: RenewContractRequest,
  token: string,
): Promise<PlayerContract> {
  return apiPost<PlayerContract>(`/save-games/${saveGameId}/players/${playerId}/renew`, req, token)
}

export function terminateContract(
  saveGameId: string,
  playerId: string,
  token: string,
): Promise<TerminationResult> {
  return apiPost<TerminationResult>(`/save-games/${saveGameId}/players/${playerId}/terminate`, {}, token)
}

export function getStaffRoster(saveGameId: string, clubId: string, token: string): Promise<StaffRoster> {
  return apiGet<StaffRoster>(`/save-games/${saveGameId}/clubs/${clubId}/staff`, token)
}

export function getStaffCandidates(
  saveGameId: string,
  clubId: string,
  role: StaffRole,
  token: string,
): Promise<StaffCandidate[]> {
  return apiGet<StaffCandidate[]>(
    `/save-games/${saveGameId}/clubs/${clubId}/staff/candidates?role=${role}`, token)
}

/**
 * Signs a candidate. The name goes along with the index because the backend re-rolls the shortlist
 * on every call — a click on a list rendered before the date advanced, or before another hire,
 * would otherwise land on whoever the reshuffle moved into that slot.
 */
export function hireStaff(
  saveGameId: string,
  clubId: string,
  role: StaffRole,
  candidateIndex: number,
  candidateName: string,
  token: string,
): Promise<StaffRoster> {
  return apiPut<StaffRoster>(
    `/save-games/${saveGameId}/clubs/${clubId}/staff/${role}`,
    { candidateIndex, candidateName }, token)
}
