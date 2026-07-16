export const TOKEN_KEY = 'carvoeiro_manager_token'
export const USER_KEY = 'carvoeiro_manager_user'

/** Clears the stored auth token and user — used on explicit logout and on a 401 from the API. */
export function clearAuthStorage(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
