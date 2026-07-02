import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { Layout } from './Layout'
import { AuthProvider } from '../auth/AuthContext'
import { getSaveGame, advanceRound, getAdvancePreview, getLineup } from '../api/career'
import { deleteAccount } from '../api/account'
import { getLiveMatch } from '../api/liveMatch'
import type { Mock } from 'vitest'
import type { AdvancePreviewResponse, AdvanceResponse, SaveGameResponse } from '../api/types'

vi.mock('../api/career', () => ({
  getSaveGame: vi.fn(),
  advanceRound: vi.fn(),
  getAdvancePreview: vi.fn(),
  getLineup: vi.fn(),
  createCareer: vi.fn(),
}))

vi.mock('../api/account', () => ({
  deleteAccount: vi.fn(),
}))

vi.mock('../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  kickoffLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
}))

const PREVIEW_OK: AdvancePreviewResponse = {
  nextEventType: 'MATCH',
  managedClubPlaysNext: true,
  ineligibleStarters: [],
  windowClosingAfterAdvance: false,
  pendingTransfers: 0,
  defaultStandingsCompetitionId: null,
  lowFitnessStarters: [],
}

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
  finance: { gate: 500_000, matchCost: 40_000, sponsorship: 0, wages: 0, upkeep: 0, prize: 0, net: 460_000, balance: 1_000_000 },
  bidsReceived: 0,
  seasonFinished: false,
}

function seedAuth(userId = 'U1', saveGameId?: string) {
  localStorage.setItem('carvoeiro_manager_token', 'test-token')
  localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'testuser', userId }))
  if (saveGameId) {
    localStorage.setItem(`carvoeiro_manager_save_game_id_${userId}`, saveGameId)
  }
}

// Renders the current location so tests can assert navigation (e.g. the Avançar redirect).
function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderLayout(initialPath = '/dashboard') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <AuthProvider>
          <Layout>
            <div>Page content</div>
            <LocationProbe />
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
    ;(getAdvancePreview as Mock).mockResolvedValue(PREVIEW_OK)
    ;(getLineup as Mock).mockResolvedValue({ lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2', starters: [] })
    ;(deleteAccount as Mock).mockResolvedValue(undefined)
    ;(getLiveMatch as Mock).mockResolvedValue(null)
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
    expect(screen.getByRole('link', { name: /histórico/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /news/i })).toBeInTheDocument()
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
    expect(localStorage.getItem('carvoeiro_manager_token')).toBeNull()
  })

  it('clicking Avançar calls advanceRound and redirects to the News tab', async () => {
    // The managed club does NOT play next → the instant advance path is taken.
    ;(getAdvancePreview as Mock).mockResolvedValue({
      ...PREVIEW_OK,
      managedClubPlaysNext: false,
    } satisfies AdvancePreviewResponse)
    const user = userEvent.setup()
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    // Wait for the save game to load so hasSaveGame becomes true and Avançar appears
    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    expect(avancarBtn).not.toBeDisabled()

    await user.click(avancarBtn)
    expect(advanceRound).toHaveBeenCalledWith('sg-1', 'test-token')

    // The advance outcome is persisted as a news item; the user is sent to /news.
    await waitFor(() => {
      expect(screen.getByTestId('location')).toHaveTextContent('/news?saveGameId=sg-1')
    })
    // No modal dialog any more — the timeline now lives on the News page.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('disables Avançar and warns when a saved starter is ineligible before a match', async () => {
    ;(getAdvancePreview as Mock).mockResolvedValue({
      ...PREVIEW_OK,
      managedClubPlaysNext: true,
      ineligibleStarters: [{ playerId: 'p9', playerName: 'Zagueiro Suspenso', reason: 'SUSPENDED' }],
    } satisfies AdvancePreviewResponse)
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    await waitFor(() => expect(avancarBtn).toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent(/suspenso|lesionado/i)
    expect(screen.getByRole('link', { name: /ajustar escalação/i })).toBeInTheDocument()
  })

  it('disables Avançar and warns when the window is closing with pending transfers', async () => {
    ;(getAdvancePreview as Mock).mockResolvedValue({
      ...PREVIEW_OK,
      managedClubPlaysNext: false,
      windowClosingAfterAdvance: true,
      pendingTransfers: 2,
    } satisfies AdvancePreviewResponse)
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    await waitFor(() => expect(avancarBtn).toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent(/janela de transferências/i)
    expect(screen.getByRole('link', { name: /ver propostas/i })).toBeInTheDocument()
  })

  it('blocks Avançar until the managed club has a saved lineup', async () => {
    ;(getLineup as Mock).mockResolvedValue(null)
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    await waitFor(() => expect(avancarBtn).toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent(/escale seu time/i)
    expect(screen.getByRole('link', { name: /escalar time/i })).toBeInTheDocument()
  })

  it('deletes the account from the gear menu after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    seedAuth()
    renderLayout()
    fireEvent.click(screen.getByRole('button', { name: /configurações/i }))
    fireEvent.click(screen.getByText('Excluir conta'))
    await waitFor(() => expect(deleteAccount).toHaveBeenCalledWith('test-token'))
    // On success the session is cleared.
    await waitFor(() => expect(localStorage.getItem('carvoeiro_manager_token')).toBeNull())
  })

  it('does not delete the account when the confirmation is cancelled', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    seedAuth()
    renderLayout()
    fireEvent.click(screen.getByRole('button', { name: /configurações/i }))
    fireEvent.click(screen.getByText('Excluir conta'))
    expect(deleteAccount).not.toHaveBeenCalled()
    expect(localStorage.getItem('carvoeiro_manager_token')).toBe('test-token')
  })

  it('shows a non-blocking low-fitness warning and keeps Avançar enabled', async () => {
    ;(getAdvancePreview as Mock).mockResolvedValue({
      ...PREVIEW_OK,
      lowFitnessStarters: [{ playerId: 'p5', playerName: 'Cansado Silva', fitness: 42 }],
    } satisfies AdvancePreviewResponse)
    seedAuth('U1', 'sg-1')
    renderLayout('/dashboard')

    const avancarBtn = await screen.findByRole('button', { name: /^avançar$/i })
    // The button must remain ENABLED — low fitness never blocks advance
    await waitFor(() => expect(avancarBtn).not.toBeDisabled())
    // The warning text should be visible
    expect(screen.getByText(/Cansado Silva/)).toBeInTheDocument()
    expect(screen.getByText(/pouca condição/i)).toBeInTheDocument()
  })
})
