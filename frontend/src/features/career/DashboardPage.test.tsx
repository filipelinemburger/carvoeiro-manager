import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from './DashboardPage'
import { AuthProvider } from '../../auth/AuthContext'
import { getSaveGame, getFinances } from '../../api/career'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  createCareer: vi.fn(),
  getFinances: vi.fn(),
}))

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <DashboardPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function seedAuth(userId: string) {
  localStorage.setItem('elifoot_token', 'test-token')
  localStorage.setItem('elifoot_user', JSON.stringify({ username: 'asd', userId }))
}

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('does not get stuck when the stored save game fails to load (offers a new career)', async () => {
    // Given a logged-in user with a stored save game id that no longer loads
    // (e.g. it belonged to a previous user — the ownership guard returns 404)
    seedAuth('U1')
    localStorage.setItem('elifoot_save_game_id_U1', 'foreign-save')
    localStorage.setItem('elifoot_save_game_id', 'foreign-save') // legacy global key
    ;(getSaveGame as Mock).mockRejectedValue(new Error('Save game not found'))

    renderDashboard()

    // Then the user can start a new career instead of being stuck on stale data
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /nova carreira/i })).toBeInTheDocument()
    })
    // and the unusable stored id is cleared
    expect(localStorage.getItem('elifoot_save_game_id_U1')).toBeNull()
  })

  it('renders the career when the stored save game loads', async () => {
    seedAuth('U2')
    localStorage.setItem('elifoot_save_game_id_U2', 'save-2')
    ;(getSaveGame as Mock).mockResolvedValue({
      id: 'save-2',
      seasonYear: 2026,
      competitionId: 'comp-1',
      competitionName: 'Campeonato Catarinense',
      managedClubId: 'club-1',
      managedClubName: 'Criciúma Esporte Clube',
      currentRound: 5,
      totalRounds: 18,
    })
    ;(getFinances as Mock).mockResolvedValue({ balance: 12500000, transactions: [] })

    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText(/Criciúma Esporte Clube/)).toBeInTheDocument()
    })
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(getSaveGame).toHaveBeenCalledWith('save-2', 'test-token')
  })

  it('keeps save games separate per user (does not load another user\'s id)', () => {
    // Given user U3 logs in but only U_OTHER has a stored save id
    seedAuth('U3')
    localStorage.setItem('elifoot_save_game_id_U_OTHER', 'someone-elses-save')

    renderDashboard()

    // Then U3 is offered a new career and never fetches the other user's save
    expect(screen.getByRole('button', { name: /nova carreira/i })).toBeInTheDocument()
    expect(getSaveGame).not.toHaveBeenCalled()
  })
})
