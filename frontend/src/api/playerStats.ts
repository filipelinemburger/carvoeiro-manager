import { apiGet } from './client'
import type { PlayerStatsResponse } from './types'

export function getPlayerStats(
  saveGameId: string,
  playerId: string,
  token: string,
): Promise<PlayerStatsResponse> {
  return apiGet<PlayerStatsResponse>(`/save-games/${saveGameId}/players/${playerId}/stats`, token)
}
