import { createContext, useContext, useState, type ReactNode } from 'react'
import { apiPost } from '../api/client'
import type { AuthResponse, LoginRequest, RegisterRequest } from '../api/types'

interface User {
  username: string
  userId: string
}

interface AuthContextValue {
  token: string | null
  user: User | null
  login: (credentials: LoginRequest) => Promise<void>
  register: (credentials: RegisterRequest) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

const TOKEN_KEY = 'elifoot_token'
const USER_KEY = 'elifoot_user'

function loadFromStorage(): { token: string | null; user: User | null } {
  const token = localStorage.getItem(TOKEN_KEY)
  const raw = localStorage.getItem(USER_KEY)
  const user: User | null = raw ? (JSON.parse(raw) as User) : null
  return { token, user }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const stored = loadFromStorage()
  const [token, setToken] = useState<string | null>(stored.token)
  const [user, setUser] = useState<User | null>(stored.user)

  const storeAuth = (resp: AuthResponse) => {
    const u: User = { username: resp.username, userId: resp.userId }
    localStorage.setItem(TOKEN_KEY, resp.token)
    localStorage.setItem(USER_KEY, JSON.stringify(u))
    setToken(resp.token)
    setUser(u)
  }

  const login = async (credentials: LoginRequest) => {
    const resp = await apiPost<AuthResponse>('/auth/login', credentials)
    storeAuth(resp)
  }

  const register = async (credentials: RegisterRequest) => {
    const resp = await apiPost<AuthResponse>('/auth/register', credentials)
    storeAuth(resp)
  }

  const logout = () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    setToken(null)
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ token, user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
