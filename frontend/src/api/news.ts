import { apiGet, apiPost } from './client'

// Unread news-item count for the nav badge.
export function getNewsUnreadCount(saveGameId: string, token: string): Promise<number> {
  return apiGet<{ count: number }>(`/save-games/${saveGameId}/news/unread-count`, token).then(r => r.count)
}

// Mark one news item read (idempotent server-side).
export function markNewsRead(saveGameId: string, newsItemId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/news/${newsItemId}/read`, {}, token)
}

// Mark every unread news item read.
export function markAllNewsRead(saveGameId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/news/read-all`, {}, token)
}
