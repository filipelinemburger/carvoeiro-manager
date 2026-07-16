import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from './DashboardPage'
import { AuthProvider } from '../../auth/AuthContext'
import { getSaveGame, getFinances, listCareers } from '../../api/career'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  createCareer: vi.fn(),
  getFinances: vi.fn(),
  listCareers: vi.fn(),
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
  localStorage.setItem('carvoeiro_manager_token', 'test-token')
  localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'asd', userId }))
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
    localStorage.setItem('carvoeiro_manager_save_game_id_U1', 'foreign-save')
    localStorage.setItem('elifoot_save_game_id', 'foreign-save') // legacy global key
    ;(getSaveGame as Mock).mockRejectedValue(new Error('Save game not found'))

    renderDashboard()

    // Then the user is shown the empty-state hint (new career lives in the ⚙️ menu) instead
    // of being stuck on stale data
    await waitFor(() => {
      expect(screen.getByText(/você ainda não tem uma carreira/i)).toBeInTheDocument()
    })
    // and the unusable stored id is cleared
    expect(localStorage.getItem('carvoeiro_manager_save_game_id_U1')).toBeNull()
  })

  it('renders the career when the stored save game loads', async () => {
    seedAuth('U2')
    localStorage.setItem('carvoeiro_manager_save_game_id_U2', 'save-2')
    ;(getSaveGame as Mock).mockResolvedValue({
      id: 'save-2',
      seasonYear: 2026,
      competitionId: 'comp-1',
      competitionName: 'Campeonato Catarinense',
      managedClubId: 'club-1',
      managedClubName: 'Criciúma Esporte Clube',
      currentRound: 5,
      totalRounds: 18,
      managerName: 'Filipe Silva',
      managerNationality: 'Brasil',
    })
    ;(getFinances as Mock).mockResolvedValue({ balance: 12500000, transactions: [] })

    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText(/Criciúma Esporte Clube/)).toBeInTheDocument()
    })
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    await waitFor(() => {
      expect(screen.getByText(/Filipe Silva \(Brasil\)/)).toBeInTheDocument()
    })
    expect(getSaveGame).toHaveBeenCalledWith('save-2', 'test-token')
  })

  it('hides the Técnico row for a save created before manager identity existed', async () => {
    seedAuth('U4')
    localStorage.setItem('carvoeiro_manager_save_game_id_U4', 'save-4')
    ;(getSaveGame as Mock).mockResolvedValue({
      id: 'save-4',
      seasonYear: 2026,
      competitionId: 'comp-1',
      competitionName: 'Campeonato Catarinense',
      managedClubId: 'club-1',
      managedClubName: 'Criciúma Esporte Clube',
      currentRound: 5,
      totalRounds: 18,
      managerName: '',
      managerNationality: '',
    })
    ;(getFinances as Mock).mockResolvedValue({ balance: 12500000, transactions: [] })

    renderDashboard()

    await waitFor(() => {
      expect(screen.getByText(/Criciúma Esporte Clube/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/Técnico:/)).not.toBeInTheDocument()
  })

  it('keeps save games separate per user (does not load another user\'s id)', () => {
    // Given user U3 logs in but only U_OTHER has a stored save id
    seedAuth('U3')
    localStorage.setItem('carvoeiro_manager_save_game_id_U_OTHER', 'someone-elses-save')

    renderDashboard()

    // Then U3 sees the empty-state hint and never fetches the other user's save
    expect(screen.getByText(/você ainda não tem uma carreira/i)).toBeInTheDocument()
    expect(getSaveGame).not.toHaveBeenCalled()
  })

  it('shows "Carregar última carreira" when there is no active career, and clicking it resumes the most recent one', async () => {
    seedAuth('U3')
    ;(listCareers as Mock).mockResolvedValue([
      { id: 'save-recent', managedClubName: 'Clube Recente', seasonYear: 2026, currentRound: 3, currentDate: '2026-02-01', status: 'IN_PROGRESS', createdAt: '2026-01-01T00:00:00Z', lastPlayedAt: '2026-02-01T00:00:00Z' },
      { id: 'save-older', managedClubName: 'Clube Antigo', seasonYear: 2025, currentRound: 10, currentDate: '2025-12-01', status: 'IN_PROGRESS', createdAt: '2025-06-01T00:00:00Z', lastPlayedAt: '2025-12-01T00:00:00Z' },
    ])

    renderDashboard()

    const button = await screen.findByRole('button', { name: /carregar última carreira/i })
    fireEvent.click(button)

    expect(localStorage.getItem('carvoeiro_manager_save_game_id_U3')).toBe('save-recent')
  })

  it('does not show "Carregar última carreira" when the user has no careers at all', async () => {
    seedAuth('U4')
    ;(listCareers as Mock).mockResolvedValue([])

    renderDashboard()

    await waitFor(() => expect(listCareers).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /carregar última carreira/i })).not.toBeInTheDocument()
  })
})
