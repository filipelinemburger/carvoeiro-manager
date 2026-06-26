import { apiGet, apiPost, apiPut } from './client'
import type {
  SaveGameResponse,
  SquadResponse,
  SetLineupRequest,
  LineupResponse,
  SimulateRoundResponse,
  StandingRow,
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
