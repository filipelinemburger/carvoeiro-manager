import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { DashboardPage } from './features/career/DashboardPage'
import { SquadPage } from './features/squad/SquadPage'
import { TablePage } from './features/competition/TablePage'
import { useAuth } from './auth/AuthContext'
import type { ReactNode } from 'react'

function ProtectedRoute({ children }: { children: ReactNode }) {
  const { token } = useAuth()
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/squad"
        element={
          <ProtectedRoute>
            <SquadPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/table"
        element={
          <ProtectedRoute>
            <TablePage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
