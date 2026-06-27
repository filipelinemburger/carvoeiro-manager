import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { DashboardPage } from './features/career/DashboardPage'
import { SquadPage } from './features/squad/SquadPage'
import { TablePage } from './features/competition/TablePage'
import { SeasonSummaryPage } from './features/season/SeasonSummaryPage'
import { SeasonHistoryPage } from './features/season/SeasonHistoryPage'
import { FinancesPage } from './features/finance/FinancesPage'
import { TransferMarketPage } from './features/transfer/TransferMarketPage'
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
      <Route path="/register" element={<RegisterPage />} />
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
      <Route
        path="/season-summary"
        element={
          <ProtectedRoute>
            <SeasonSummaryPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/history"
        element={
          <ProtectedRoute>
            <SeasonHistoryPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/finances"
        element={
          <ProtectedRoute>
            <FinancesPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/transfers"
        element={
          <ProtectedRoute>
            <TransferMarketPage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
