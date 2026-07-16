import { clearAuthStorage } from '../auth/authStorage'

const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8088/api/v1'

/** An HTTP failure, preserving the backend's error code so callers can react to it precisely. */
export class ApiError extends Error {
  readonly code: string | null
  readonly status: number

  constructor(message: string, code: string | null, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

async function handle<T>(res: Response, path: string): Promise<T> {
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string; message?: string }
    // A 401 means the stored token is missing/expired/invalid — except on the auth endpoints
    // themselves, where a 401 just means "wrong username/password" and must surface as an
    // inline form error, not wipe storage and bounce the user away from the login page.
    if (res.status === 401 && !path.startsWith('/auth/')) {
      clearAuthStorage()
      window.location.href = '/login'
    }
    throw new ApiError(body.message ?? res.statusText ?? 'Request failed', body.error ?? null, res.status)
  }
  return res.status === 204 ? (undefined as T) : (res.json() as Promise<T>)
}

export async function apiGet<T>(path: string, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return handle<T>(res, path)
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  return handle<T>(res, path)
}

export async function apiPut<T>(path: string, body: unknown, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  return handle<T>(res, path)
}

export async function apiDelete<T>(path: string, token?: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  return handle<T>(res, path)
}
