import { apiGet, apiPost } from './client'
import type { LiveMatchState, SubstituteRequest, AdvanceResponse } from './types'

export function kickoffLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/kickoff`, {}, token)
}

// Resume-on-reload: null when no live match is in progress (endpoint replies 204 -> undefined).
export function getLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState | null> {
  return apiGet<LiveMatchState | undefined>(`/save-games/${saveGameId}/live-match`, token).then(r => r ?? null)
}

export function advanceLiveMatch(saveGameId: string, token: string): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/advance`, {}, token)
}

export function substituteLiveMatch(
  saveGameId: string, req: SubstituteRequest, token: string,
): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/substitute`, req, token)
}

export function finishLiveMatch(saveGameId: string, token: string): Promise<AdvanceResponse> {
  return apiPost<AdvanceResponse>(`/save-games/${saveGameId}/live-match/finish`, {}, token)
}
