import type { QueryClient } from '@tanstack/react-query'
import type { NavigateFunction } from 'react-router-dom'

/**
 * Sets the given career as active for this user and navigates to the dashboard. Shared by
 * LoadCareerDialog's per-row "Carregar" button and the Dashboard's "Carregar última carreira"
 * shortcut so both do exactly the same three things.
 */
export function resumeCareer(
  saveGameId: string,
  storageKey: string,
  queryClient: QueryClient,
  navigate: NavigateFunction,
): void {
  localStorage.setItem(storageKey, saveGameId)
  queryClient.invalidateQueries({ queryKey: ['saveGame'] })
  void navigate('/dashboard')
}
