import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TablePage } from './TablePage'
import { AuthProvider } from '../../auth/AuthContext'
import type { StandingRow } from '../../api/types'

vi.mock('../../api/career', () => ({
  getStandings: vi.fn(),
}))

import { getStandings } from '../../api/career'

const mockGetStandings = vi.mocked(getStandings)

const MANAGED_CLUB_ID = 'club-cri'

const standings: StandingRow[] = [
  {
    clubId: MANAGED_CLUB_ID,
    clubName: 'Criciúma',
    played: 3,
    won: 3,
    drawn: 0,
    lost: 0,
    goalsFor: 7,
    goalsAgainst: 2,
    goalDifference: 5,
    points: 9,
  },
  {
    clubId: 'club-avi',
    clubName: 'Avaí',
    played: 3,
    won: 1,
    drawn: 1,
    lost: 1,
    goalsFor: 4,
    goalsAgainst: 4,
    goalDifference: 0,
    points: 4,
  },
  {
    clubId: 'club-flo',
    clubName: 'Figueirense',
    played: 3,
    won: 0,
    drawn: 0,
    lost: 3,
    goalsFor: 1,
    goalsAgainst: 6,
    goalDifference: -5,
    points: 0,
  },
]

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderWithProviders(saveGameId = 'sg-1', competitionId = 'comp-1', managedClubId = MANAGED_CLUB_ID) {
  const qc = makeQueryClient()
  return render(
    <MemoryRouter
      initialEntries={[
        `/table?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`,
      ]}
    >
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TablePage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('TablePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStandings.mockResolvedValue(standings)
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders all rows with points column', async () => {
    renderWithProviders()
    await waitFor(() => {
      expect(screen.getByText('Criciúma')).toBeInTheDocument()
    })
    expect(screen.getByText('Avaí')).toBeInTheDocument()
    expect(screen.getByText('Figueirense')).toBeInTheDocument()

    // Points column values — use getAllByText since numbers may appear in multiple cells
    expect(screen.getAllByText('9').length).toBeGreaterThan(0)
    expect(screen.getAllByText('4').length).toBeGreaterThan(0)
    expect(screen.getAllByText('0').length).toBeGreaterThan(0)
  })

  it('renders rows in the order returned by the API (by position)', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Criciúma'))

    const rows = screen.getAllByRole('row')
    // Row 0 is the header; rows 1, 2, 3 are data rows
    expect(rows[1]).toHaveTextContent('Criciúma')
    expect(rows[2]).toHaveTextContent('Avaí')
    expect(rows[3]).toHaveTextContent('Figueirense')
  })

  it('highlights the managed club row', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Criciúma'))

    const rows = screen.getAllByRole('row')
    const criciuRow = rows[1]
    expect(criciuRow).toHaveAttribute('data-managed', 'true')
  })

  it('does not refetch the standings on a second mount when the query client is reused', async () => {
    const qc = makeQueryClient()
    const renderOnce = () => render(
      <MemoryRouter initialEntries={[`/table?saveGameId=sg-1&competitionId=comp-1&managedClubId=${MANAGED_CLUB_ID}`]}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TablePage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Criciúma')).toBeInTheDocument())
    expect(mockGetStandings).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Criciúma')).toBeInTheDocument())
    expect(mockGetStandings).toHaveBeenCalledTimes(1)
  })
})
