import { apiGet, apiPost } from './client'
import type { LiveMatchState, SubstituteBatchRequest, AdvanceResponse, LiveTacticsRequest } from './types'

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
  saveGameId: string, req: SubstituteBatchRequest, token: string,
): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/substitutions`, req, token)
}

export function finishLiveMatch(saveGameId: string, token: string): Promise<AdvanceResponse> {
  return apiPost<AdvanceResponse>(`/save-games/${saveGameId}/live-match/finish`, {}, token)
}

export function changeTactics(saveGameId: string, req: LiveTacticsRequest, token: string): Promise<LiveMatchState> {
  return apiPost<LiveMatchState>(`/save-games/${saveGameId}/live-match/tactics`, req, token)
}
