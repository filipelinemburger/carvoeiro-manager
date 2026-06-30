import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { Layout } from './Layout'
import { AuthProvider } from '../auth/AuthContext'
import { getSaveGame, advanceRound } from '../api/career'
import type { Mock } from 'vitest'
import type { AdvanceResponse, SaveGameResponse } from '../api/types'

vi.mock('../api/career', () => ({
  getSaveGame: vi.fn(),
  advanceRound: vi.fn(),
}))

const SAVE_GAME: SaveGameResponse = {
  id: 'sg-1',
  seasonYear: 2026,
  competitionId: 'comp-1',
  competitionName: 'Campeonato Catarinense',
  managedClubId: 'club-1',
  managedClubName: 'Criciúma',
  currentRound: 5,
  totalRounds: 18,
  currentDate: '2026-04-01',
  competitions: [],
}

const ADVANCE_RESPONSE: AdvanceResponse = {
  type: 'MATCH',
  date: '2026-04-15',
  competitionName: 'Campeonato Catarinense',
  round: 5,
  results: [
    {
      fixtureId: 'f1',
      homeClubId: 'c1',
      homeClubName: 'Criciúma',
      homeGoals: 2,
      awayClubId: 'c2',
      awayClubName: 'Figueirense',
      awayGoals: 1,
    },
  ],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: { gate: 500_000, sponsorship: 100_000, wages: 200_000, upkeep: 50_000, prize: 0, net: 350_000, balance: 1_000_000 },
  bidsReceived: 0,
  seasonFinished: false,
}

function seedAuth(userId = 'U1', saveGameId?: string) {
  localStorage.setItem('elifoot_token', 'test-token')
  localStorage.setItem('elifoot_user', JSON.stringify({ username: 'testuser', userId }))
  if (saveGameId) {
    localStorage.setItem(`elifoot_save_game_id_${userId}`, saveGameId)
  }
}

function renderLayout(initialPath = '/dashboard') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>
          <Layout>
            <div>Page content</div>
          </Layout>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Layout shell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    ;(getSaveGame as Mock).mockResolvedValue(SAVE_GAME)
    ;(advanceRound as Mock).mockResolvedValue(ADVANCE_RESPONSE)
  })

  it('renders children inside a content panel', () => {
    seedAuth()
    renderLayout()
    expect(screen.getByText('Page content')).toBeInTheDocument()
  })

  it('shows the Início nav link on every protected page', () => {
    seedAuth()
    renderLayout('/dashboard')
    expect(screen.getByRole('link', { name: /início/i })).toBeInTheDocument()
  })

  it('hides the back button on /dashboard', () => {
    seedAuth()
    renderLayout('/dashboard')
    expect(screen.queryByRole('button', { name: /voltar/i })).not.toBeInTheDocument()
  })

  it('shows the back button on pages other than /dashboard', () => {
    seedAuth('U1', 'sg-1')
    renderLayout('/squad?saveGameId=sg-1&clubId=club-1')
    expect(screen.getByRole('button', { name: /voltar/i })).toBeInTheDocument()
  })

  it('shows nav links when save game exists', async () => {
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    await waitFor(() => {
      expect(screen.getByRole('link', { name: /escalação/i })).toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /classificação/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /finanças/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /mercado/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /livres/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /histórico/i })).toBeInTheDocument()
  })

  it('shows Avançar button when save game exists', async () => {
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /avançar/i })).toBeInTheDocument()
    })
  })

  it('hides per-save nav links when no save game', () => {
    seedAuth('U1') // no save game id stored
    ;(getSaveGame as Mock).mockResolvedValue(undefined)
    renderLayout('/dashboard')

    expect(screen.queryByRole('link', { name: /escalação/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /avançar/i })).not.toBeInTheDocument()
  })

  it('gear icon toggles the Sair menu item', () => {
    seedAuth()
    renderLayout()
    const gear = screen.getByRole('button', { name: /configurações/i })
    expect(screen.queryByText('Sair')).not.toBeInTheDocument()
    fireEvent.click(gear)
    expect(screen.getByText('Sair')).toBeInTheDocument()
  })

  it('clicking Sair calls logout and redirects', () => {
    seedAuth()
    renderLayout()
    fireEvent.click(screen.getByRole('button', { name: /configurações/i }))
    fireEvent.click(screen.getByText('Sair'))
    // After logout, token is cleared
    expect(localStorage.getItem('elifoot_token')).toBeNull()
  })

  it('clicking Avançar calls advanceRound and opens the timeline modal', async () => {
    const user = userEvent.setup()
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    // Wait for the save game to load so hasSaveGame becomes true and Avançar appears
    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    expect(avancarBtn).not.toBeDisabled()

    await user.click(avancarBtn)
    expect(advanceRound).toHaveBeenCalledWith('sg-1', 'test-token')

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

  it('timeline modal is dismissed via the Fechar button', async () => {
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    await waitFor(() => {
      const btn = screen.queryByRole('button', { name: /^avançar$/i })
      expect(btn).toBeInTheDocument()
      expect(btn).not.toBeDisabled()
    })

    fireEvent.click(screen.getByRole('button', { name: /^avançar$/i }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // Click the bottom "Fechar" button (not the X icon button)
    fireEvent.click(screen.getByRole('button', { name: /^fechar$/i }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})
