import { apiGet, apiPost, apiPut, apiDelete } from './client'
import type {
  SaveGameResponse,
  SaveGameSummary,
  SquadResponse,
  SetLineupRequest,
  LineupResponse,
  AdvanceResponse,
  AdvancePreviewResponse,
  StandingRow,
  TacticsRequest,
  TacticsResponse,
  TopScorerResponse,
  RolloverResponse,
  SeasonSummary,
  SeasonDetail,
  FinancesResponse,
  MarketPlayer,
  TransferOffer,
  MakeOfferRequest,
  RespondOfferRequest,
  FreeAgent,
  RenewContractRequest,
  SignFreeAgentRequest,
  PlayerContract,
} from './types'

export function createCareer(token: string): Promise<SaveGameResponse> {
  return apiPost<SaveGameResponse>('/save-games', {}, token)
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

// Persisted news feed: every advance is stored and returned here, most recent first.
export function getNews(saveGameId: string, token: string): Promise<AdvanceResponse[]> {
  return apiGet<AdvanceResponse[]>(`/save-games/${saveGameId}/news`, token)
}

// Look-ahead at the next advance so the UI can disable "Avançar" before the click.
export function getAdvancePreview(saveGameId: string, token: string): Promise<AdvancePreviewResponse> {
  return apiGet<AdvancePreviewResponse>(`/save-games/${saveGameId}/advance-preview`, token)
}

export function getStandings(
  saveGameId: string,
  competitionId: string,
  token: string,
): Promise<StandingRow[]> {
  return apiGet<StandingRow[]>(
    `/save-games/${saveGameId}/competitions/${competitionId}/standings`,
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

export function getMarket(saveGameId: string, token: string): Promise<MarketPlayer[]> {
  return apiGet<MarketPlayer[]>(`/save-games/${saveGameId}/transfers/market`, token)
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
