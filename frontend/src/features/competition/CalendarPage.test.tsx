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
    { id: 'comp-2', name: 'Copa SC', type: 'CUP', seasonYear: 2026, totalRounds: 4 },
  ],
}

// Catarinense fixtures — all home for the managed club (Criciúma).
const catarinenseFixtures: FixtureEntry[] = [
  {
    fixtureId: 'f-1',
    round: 1,
    matchDate: '2026-03-01',
    home: true,
    opponentClubId: 'club-avi',
    opponentName: 'Avaí Futebol Clube',
    opponentShortName: 'Avaí',
    stadiumName: 'Estádio Heriberto Hülse',
    played: true,
    goalsFor: 3,
    goalsAgainst: 1,
  },
  {
    fixtureId: 'f-3',
    round: 2,
    matchDate: '2026-03-15',
    home: true,
    opponentClubId: 'club-cha',
    opponentName: 'Chapecoense',
    opponentShortName: 'Chapecoense',
    stadiumName: 'Estádio Heriberto Hülse',
    played: true,
    goalsFor: 1,
    goalsAgainst: 1,
  },
  {
    fixtureId: 'f-5',
    round: 3,
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

// Copa SC fixtures — all away for the managed club (Criciúma).
const copaScFixtures: FixtureEntry[] = [
  {
    fixtureId: 'f-2',
    round: 1,
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
    fixtureId: 'f-4',
    round: 2,
    matchDate: '2026-03-22',
    home: false,
    opponentClubId: 'club-joi',
    opponentName: 'Joinville Esporte Clube',
    opponentShortName: 'Joinville',
    stadiumName: 'Estádio Arena Joinville',
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
    mockGetSaveGame.mockResolvedValue(saveGame)
    mockGetFixtures.mockImplementation((_saveGameId, competitionId) => {
      if (competitionId === 'comp-1') return Promise.resolve(catarinenseFixtures)
      if (competitionId === 'comp-2') return Promise.resolve(copaScFixtures)
      return Promise.resolve([])
    })
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('does not render a Rodada column or a competition selector', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getAllByText('Catarinense').length).toBeGreaterThan(0))

    expect(screen.queryByText('RODADA')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Campeonato')).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('merges fixtures from every competition into one table sorted by date ascending', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6)) // header + 5 fixtures

    const rows = screen.getAllByRole('row').slice(1)
    const dates = rows.map(r => r.textContent?.match(/\d{2}\/\d{2}\/\d{4}/)?.[0])
    expect(dates).toEqual(['01/03/2026', '08/03/2026', '15/03/2026', '22/03/2026', '29/03/2026'])

    // both competitions are represented in the single merged table
    expect(screen.getAllByText('Catarinense').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Copa SC').length).toBeGreaterThan(0)
  })

  it('renders a HOME fixture as "{managed} x {opponent}" with score "hg x ag"', async () => {
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText('Criciúma x Avaí Futebol Clube')).toBeInTheDocument(),
    )
    expect(screen.getByText('3x1')).toBeInTheDocument()
  })

  it('renders an AWAY fixture as "{opponent} x {managed}" with score "ag x hg"', async () => {
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText('Figueirense Futebol Clube x Criciúma')).toBeInTheDocument(),
    )
    expect(screen.getByText('2x0')).toBeInTheDocument()
  })

  it('strips the leading "Estádio " prefix from the stadium name', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getAllByText('Heriberto Hülse').length).toBe(3))
    expect(screen.getByText('Orlando Scarpelli')).toBeInTheDocument()
    expect(screen.getByText('Arena Joinville')).toBeInTheDocument()
    expect(screen.queryByText('Estádio Heriberto Hülse')).not.toBeInTheDocument()
    expect(screen.queryByText('Estádio Arena Joinville')).not.toBeInTheDocument()
  })

  it('shows a dash for the score of unplayed fixtures', async () => {
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText('Joinville Esporte Clube x Criciúma')).toBeInTheDocument(),
    )
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBe(2)
  })

  it('highlights the first unplayed fixture row (by date)', async () => {
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText('Joinville Esporte Clube x Criciúma')).toBeInTheDocument(),
    )

    const rows = screen.getAllByRole('row')
    const joinvilleRow = rows.find(r => r.textContent?.includes('Joinville'))
    const brusqueRow = rows.find(r => r.textContent?.includes('Brusque'))
    expect(joinvilleRow).toHaveAttribute('data-next-unplayed', 'true')
    expect(brusqueRow).not.toHaveAttribute('data-next-unplayed', 'true')
  })
})
