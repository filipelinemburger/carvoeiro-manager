import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AdvancePanel } from './AdvancePanel'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({ advanceRound: vi.fn() }))
import { advanceRound } from '../../api/career'

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <AdvancePanel saveGameId="sg-1" competitionId="c-1" managedClubId="club-1" />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

const FINANCE = { gate: 1935000, sponsorship: 560000, wages: 1352000, upkeep: 172000, prize: 0, net: 971000, balance: 12500000 }

describe('AdvancePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows bookings and suspensions after advancing', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 3,
      results: [],
      seasonFinished: false,
      bookings: [{ playerId: 'p1', playerName: 'Bruto Um', type: 'RED_CARD' }],
      newSuspensions: [{ playerId: 'p1', playerName: 'Bruto Um', matches: 1 }],
      finance: FINANCE,
    })

    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))

    await waitFor(() => expect(screen.getByText(/Cartões/)).toBeInTheDocument())
    expect(screen.getByText(/Bruto Um — Vermelho/)).toBeInTheDocument()
    expect(screen.getByText(/Suspensões/)).toBeInTheDocument()
  })

  it('links to the season summary when the season finishes', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 18, results: [], seasonFinished: true, bookings: [], newSuspensions: [],
      finance: { ...FINANCE, prize: 5000000, balance: 17500000 },
    })
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))
    await waitFor(() => expect(screen.getByText(/Temporada encerrada/i)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
  })

  it('shows the round finance summary after advancing', async () => {
    vi.mocked(advanceRound).mockResolvedValue({
      round: 4, results: [], seasonFinished: false, bookings: [], newSuspensions: [],
      finance: { gate: 1935000, sponsorship: 560000, wages: 1352000, upkeep: 172000, prize: 0, net: 971000, balance: 12500000 },
    })
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: /avançar rodada/i }))
    await waitFor(() => expect(screen.getByText(/Finanças da rodada/i)).toBeInTheDocument())
    expect(screen.getByText(/Saldo:/)).toBeInTheDocument()
  })
})
