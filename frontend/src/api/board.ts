import { apiGet } from './client'
import type { BoardResponse, BoardSeasonRecord } from './types'

// Where the manager stands with the board: confidence, objectives and recent changes.
export function getBoard(saveGameId: string, token: string): Promise<BoardResponse> {
  return apiGet<BoardResponse>(`/save-games/${saveGameId}/board`, token)
}

// Every season's objectives and outcomes, oldest first — the career record.
export function getBoardHistory(saveGameId: string, token: string): Promise<BoardSeasonRecord[]> {
  return apiGet<BoardSeasonRecord[]>(`/save-games/${saveGameId}/board/history`, token)
}
