import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { CalendarPage } from './CalendarPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { FixtureEntry, SaveGameResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getFixtures: vi.fn(),
  getSaveGame: vi.fn(),
}))

import { getFixtures, getSaveGame } from '../../api/career'

const mockGetFixtures = vi.mocked(getFixtures)
const mockGetSaveGame = vi.mocked(getSaveGame)

const saveGame: SaveGameResponse = {
  id: 'sg-1',
  seasonYear: 2026,
  competitionId: 'comp-1',
  competitionName: 'Catarinense',
  managedClubId: 'club-cri',
  managedClubName: 'Criciúma',
  currentRound: 4,
  totalRounds: 10,
  competitions: [
    { id: 'comp-1', name: 'Catarinense', type: 'LEAGUE', seasonYear: 2026, totalRounds: 10 },
  ],
}

const fixtures: FixtureEntry[] = [
  {
    fixtureId: 'f-1',
    round: 1,
    matchDate: '2026-03-01',
    home: true,
    opponentClubId: 'club-avi',
    opponentName: 'Avaí Futebol Clube',
    opponentShortName: 'Avaí',
    stadiumName: 'Heriberto Hülse',
    played: true,
    goalsFor: 3,
    goalsAgainst: 1,
  },
  {
    fixtureId: 'f-2',
    round: 2,
    matchDate: '2026-03-08',
    home: false,
    opponentClubId: 'club-flo',
    opponentName: 'Figueirense Futebol Clube',
    opponentShortName: 'Figueirense',
    stadiumName: 'Orlando Scarpelli',
    played: true,
    goalsFor: 0,
    goalsAgainst: 2,
  },
  {
    fixtureId: 'f-3',
    round: 3,
    matchDate: '2026-03-15',
    home: true,
    opponentClubId: 'club-cha',
    opponentName: 'Chapecoense',
    opponentShortName: 'Chapecoense',
    stadiumName: 'Heriberto Hülse',
    played: true,
    goalsFor: 1,
    goalsAgainst: 1,
  },
  {
    fixtureId: 'f-4',
    round: 4,
    matchDate: '2026-03-22',
    home: false,
    opponentClubId: 'club-joi',
    opponentName: 'Joinville Esporte Clube',
    opponentShortName: 'Joinville',
    stadiumName: 'Arena Joinville',
    played: false,
    goalsFor: null,
    goalsAgainst: null,
  },
  {
    fixtureId: 'f-5',
    round: 5,
    matchDate: '2026-03-29',
    home: true,
    opponentClubId: 'club-brs',
    opponentName: 'Brusque Futebol Clube',
    opponentShortName: 'Brusque',
    stadiumName: 'Heriberto Hülse',
    played: false,
    goalsFor: null,
    goalsAgainst: null,
  },
]

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderWithProviders(saveGameId = 'sg-1', competitionId = 'comp-1') {
  const qc = makeQueryClient()
  return render(
    <MemoryRouter initialEntries={[`/calendar?saveGameId=${saveGameId}&competitionId=${competitionId}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <CalendarPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('CalendarPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetFixtures.mockResolvedValue(fixtures)
    mockGetSaveGame.mockResolvedValue(saveGame)
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders one row per fixture with round, opponent and stadium', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Avaí (C)')).toBeInTheDocument())
    expect(screen.getByText('Figueirense (F)')).toBeInTheDocument()
    expect(screen.getByText('Chapecoense (C)')).toBeInTheDocument()
    expect(screen.getByText('Joinville (F)')).toBeInTheDocument()
    expect(screen.getByText('Brusque (C)')).toBeInTheDocument()
    expect(screen.getAllByText('Heriberto Hülse').length).toBe(3)
    expect(screen.getByText('Orlando Scarpelli')).toBeInTheDocument()
  })

  it('colors the result by win/loss/draw and shows a dash for unplayed fixtures', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('3–1')).toBeInTheDocument())

    const win = screen.getByText('3–1')
    expect(win.getAttribute('data-result')).toBe('WIN')

    const loss = screen.getByText('0–2')
    expect(loss.getAttribute('data-result')).toBe('LOSS')

    const draw = screen.getByText('1–1')
    expect(draw.getAttribute('data-result')).toBe('DRAW')

    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBe(2)
  })

  it('highlights the first unplayed round row', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joinville (F)')).toBeInTheDocument())

    const rows = screen.getAllByRole('row')
    // header + 5 data rows; round 4 (Joinville) is the first unplayed one
    const joinvilleRow = rows.find(r => r.textContent?.includes('Joinville'))
    const brusqueRow = rows.find(r => r.textContent?.includes('Brusque'))
    expect(joinvilleRow).toHaveAttribute('data-next-unplayed', 'true')
    expect(brusqueRow).not.toHaveAttribute('data-next-unplayed', 'true')
  })
})
