import { apiDelete } from './client'

/**
 * Permanently deletes the authenticated user's account and every piece of data
 * tied to it (all save games and their clubs, players, finances, etc.).
 */
export function deleteAccount(token: string): Promise<void> {
  return apiDelete('/auth/account', token)
}
