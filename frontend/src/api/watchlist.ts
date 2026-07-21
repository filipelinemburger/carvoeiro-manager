import { apiDelete, apiGet, apiPost } from './client'
import type { WatchlistPlayer } from './types'

export function getWatchlist(saveGameId: string, token: string): Promise<WatchlistPlayer[]> {
  return apiGet<WatchlistPlayer[]>(`/save-games/${saveGameId}/watchlist`, token)
}

export function addToWatchlist(saveGameId: string, playerId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/watchlist/${playerId}`, {}, token)
}

export function removeFromWatchlist(saveGameId: string, playerId: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${saveGameId}/watchlist/${playerId}`, token)
}
