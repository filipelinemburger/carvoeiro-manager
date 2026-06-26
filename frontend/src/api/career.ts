import { apiGet, apiPost, apiPut } from './client'
import type {
  SaveGameResponse,
  SquadResponse,
  SetLineupRequest,
  LineupResponse,
  SimulateRoundResponse,
  StandingRow,
  TacticsRequest,
  TacticsResponse,
  TopScorerResponse,
  RolloverResponse,
  SeasonSummary,
  SeasonDetail,
} from './types'

export function createCareer(token: string): Promise<SaveGameResponse> {
  return apiPost<SaveGameResponse>('/save-games', {}, token)
}

export function getSaveGame(id: string, token: string): Promise<SaveGameResponse> {
  return apiGet<SaveGameResponse>(`/save-games/${id}`, token)
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

export function advanceRound(saveGameId: string, token: string): Promise<SimulateRoundResponse> {
  return apiPost<SimulateRoundResponse>(`/save-games/${saveGameId}/advance`, {}, token)
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
