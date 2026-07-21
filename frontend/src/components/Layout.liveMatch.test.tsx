import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { Layout } from './Layout'
import { AuthProvider } from '../auth/AuthContext'
import { getSaveGame, getAdvancePreview, getLineup } from '../api/career'
import { getLiveMatch, kickoffLiveMatch } from '../api/liveMatch'
import { deleteAccount } from '../api/account'
import type { Mock } from 'vitest'
import type { AdvancePreviewResponse, SaveGameResponse } from '../api/types'

vi.mock('../api/career', () => ({
  getSaveGame: vi.fn(),
  advanceRound: vi.fn(),
  getAdvancePreview: vi.fn(),
  getLineup: vi.fn(),
  createCareer: vi.fn(),
}))

vi.mock('../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  kickoffLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
}))

vi.mock('../api/account', () => ({
  deleteAccount: vi.fn(),
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

const PREVIEW_PLAYS_NEXT: AdvancePreviewResponse = {
  nextEventType: 'MATCH',
  managedClubPlaysNext: true,
  ineligibleStarters: [],
  windowClosingAfterAdvance: false,
  pendingTransfers: 0,
  defaultStandingsCompetitionId: null,
  lowFitnessStarters: [],
}

const LINEUP_SAVED = { lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2', starters: [] }

function seedAuth(userId = 'U1', saveGameId = 'sg-1') {
  localStorage.setItem('carvoeiro_manager_token', 'test-token')
  localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'testuser', userId }))
  localStorage.setItem(`carvoeiro_manager_save_game_id_${userId}`, saveGameId)
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

describe('Layout — live match fork', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    ;(getSaveGame as Mock).mockResolvedValue(SAVE_GAME)
    ;(getAdvancePreview as Mock).mockResolvedValue(PREVIEW_PLAYS_NEXT)
    ;(getLineup as Mock).mockResolvedValue(LINEUP_SAVED)
    ;(deleteAccount as Mock).mockResolvedValue(undefined)
  })

  it('shows "Ir para o jogo" and opens a confirmation dialog instead of kicking off immediately', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    seedAuth()
    renderLayout()
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))

    expect(screen.getByRole('heading', { name: 'Ir para o jogo' })).toBeInTheDocument()
    expect(screen.getByText('Começar a partida contra o próximo adversário?')).toBeInTheDocument()
    expect(kickoffLiveMatch).not.toHaveBeenCalled()
  })

  it('kicks off the live match and navigates to /match only after confirming', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    ;(kickoffLiveMatch as Mock).mockResolvedValue({ fixtureId: 'f1' } as never)
    seedAuth()
    renderLayout()
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Começar partida' }))
    await waitFor(() => expect(kickoffLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })

  it('does not kick off when the confirmation dialog is cancelled', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    seedAuth()
    renderLayout()
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(kickoffLiveMatch).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows Retomar partida when a live match is in progress', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue({ fixtureId: 'f1', status: 'IN_PROGRESS' } as never)
    seedAuth()
    renderLayout()
    expect(await screen.findByRole('button', { name: /retomar partida/i })).toBeInTheDocument()
  })

  it('hides the top-bar advance/resume button on the live match screen', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue({ fixtureId: 'f1', status: 'IN_PROGRESS' } as never)
    seedAuth()
    renderLayout('/match?saveGameId=sg-1')
    // The save game has loaded (nav links appear), so the bar is fully populated…
    await screen.findByRole('link', { name: 'Escalação' })
    // …yet the redundant top-bar button is not rendered while watching the match.
    expect(screen.queryByRole('button', { name: /retomar partida/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /avançar/i })).not.toBeInTheDocument()
  })

  it('does not double-submit kickoff when the confirm button is clicked repeatedly before the mutation settles', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    // Keep the mutation pending so the confirm button stays disabled across the extra clicks.
    let resolveKickoff: (value: unknown) => void = () => {}
    ;(kickoffLiveMatch as Mock).mockImplementation(
      () => new Promise((resolve) => { resolveKickoff = resolve }),
    )
    seedAuth()
    renderLayout()
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))
    const confirmBtn = screen.getByRole('button', { name: 'Começar partida' })

    fireEvent.click(confirmBtn)
    fireEvent.click(confirmBtn)
    fireEvent.click(confirmBtn)

    await waitFor(() => expect(kickoffLiveMatch).toHaveBeenCalledTimes(1))
    resolveKickoff({ fixtureId: 'f1' })
  })

  it('closes the confirm dialog when navigating to the live match screen while it is still open', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    seedAuth()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        {/* Two history entries so the back button lands on the match screen, simulating
            the user navigating away without confirming or cancelling the dialog. The current
            entry can't be /dashboard — the back arrow is hidden there. */}
        <MemoryRouter
          initialEntries={['/match?saveGameId=sg-1', '/squad?saveGameId=sg-1&clubId=club-1']}
          initialIndex={1}
        >
          <AuthProvider>
            <Layout><div>Page content</div></Layout>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    // MUI marks the rest of the tree aria-hidden while the dialog is open, so the (still
    // present) back button must be queried with `hidden: true` to find it.
    fireEvent.click(screen.getByRole('button', { name: /voltar/i, hidden: true }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('does not kick off when a background preview refetch flips managedClubPlaysNext while the dialog is open', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    seedAuth()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/dashboard']}>
          <AuthProvider>
            <Layout><div>Page content</div></Layout>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Ir para o jogo' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    // Simulate a background refetch (e.g. the fixture got postponed) landing while the
    // confirmation dialog is still open — the confirm button must re-check, not fire blindly.
    queryClient.setQueryData(['advancePreview', 'sg-1'], { ...PREVIEW_PLAYS_NEXT, managedClubPlaysNext: false })

    fireEvent.click(screen.getByRole('button', { name: 'Começar partida' }))

    expect(kickoffLiveMatch).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
