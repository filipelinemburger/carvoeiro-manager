import { describe, it, expect, vi, afterEach } from 'vitest'
import { apiGet, apiPost, ApiError } from './client'

describe('client 401 handling', () => {
  const originalLocation = window.location

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation })
    vi.unstubAllGlobals()
  })

  // A minimal stand-in, not a spread of the real Location object — jsdom's Location has
  // mostly non-enumerable properties, so spreading it is unreliable. The code under test only
  // ever assigns to `.href`, so that's all this stub needs.
  function stubLocation() {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { href: '' },
    })
  }

  function stubFetch401(errorCode: string) {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: errorCode, message: 'nope' }), { status: 401 }),
      ),
    )
  }

  it('clears auth storage and redirects to /login on a 401 from a protected endpoint', async () => {
    stubLocation()
    localStorage.setItem('carvoeiro_manager_token', 'stale-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    stubFetch401('UNAUTHORIZED')

    await expect(apiGet('/save-games', 'stale-token')).rejects.toBeInstanceOf(ApiError)

    expect(localStorage.getItem('carvoeiro_manager_token')).toBeNull()
    expect(localStorage.getItem('carvoeiro_manager_user')).toBeNull()
    expect(window.location.href).toBe('/login')
  })

  it('does NOT clear storage or redirect on a 401 from /auth/login (bad credentials)', async () => {
    stubLocation()
    localStorage.setItem('carvoeiro_manager_token', 'stale-token')
    stubFetch401('BAD_CREDENTIALS')

    await expect(apiPost('/auth/login', { username: 'x', password: 'y' })).rejects.toBeInstanceOf(ApiError)

    expect(localStorage.getItem('carvoeiro_manager_token')).toBe('stale-token')
    expect(window.location.href).toBe('')
  })

  it('does NOT clear storage or redirect on a 401 from /auth/register', async () => {
    stubLocation()
    localStorage.setItem('carvoeiro_manager_token', 'stale-token')
    stubFetch401('USERNAME_TAKEN')

    await expect(apiPost('/auth/register', { username: 'x', password: 'y' })).rejects.toBeInstanceOf(ApiError)

    expect(localStorage.getItem('carvoeiro_manager_token')).toBe('stale-token')
    expect(window.location.href).toBe('')
  })

  it('does not touch storage or location on a non-401 error', async () => {
    stubLocation()
    localStorage.setItem('carvoeiro_manager_token', 'stale-token')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: 'SAVE_GAME_NOT_FOUND', message: 'nope' }), { status: 404 }),
      ),
    )

    await expect(apiGet('/save-games/x', 'stale-token')).rejects.toBeInstanceOf(ApiError)

    expect(localStorage.getItem('carvoeiro_manager_token')).toBe('stale-token')
    expect(window.location.href).toBe('')
  })
})
