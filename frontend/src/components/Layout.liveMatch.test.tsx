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

  it('kicks off the live match and navigates to /match when the managed club plays', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue(null)
    ;(kickoffLiveMatch as Mock).mockResolvedValue({ fixtureId: 'f1' } as never)
    seedAuth()
    renderLayout()
    fireEvent.click(await screen.findByRole('button', { name: /avançar/i }))
    await waitFor(() => expect(kickoffLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })

  it('shows Retomar partida when a live match is in progress', async () => {
    ;(getLiveMatch as Mock).mockResolvedValue({ fixtureId: 'f1', status: 'IN_PROGRESS' } as never)
    seedAuth()
    renderLayout()
    expect(await screen.findByRole('button', { name: /retomar partida/i })).toBeInTheDocument()
  })
})
