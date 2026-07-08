import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NewsPage } from './NewsPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { AdvanceResponse, SaveGameResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getNews: vi.fn(),
  getSaveGame: vi.fn(),
}))

import { getNews, getSaveGame } from '../../api/career'

const SAVE_GAME: SaveGameResponse = {
  id: 'sg-1',
  seasonYear: 2026,
  competitionId: 'comp-1',
  competitionName: 'Campeonato Catarinense',
  managedClubId: 'club-1',
  managedClubName: 'Criciúma EC',
  currentRound: 5,
  totalRounds: 20,
}

const TRANSFER_ITEM_WITH_TRADES: AdvanceResponse = {
  type: 'TRANSFER',
  date: '2026-07-01',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 1,
  seasonFinished: false,
  transfers: [
    { playerName: 'Zagueirão', fromClubName: 'Avaí', toClubName: 'Joinville', fee: 1_200_000 },
  ],
}

// Simulates a news payload persisted before the `transfers` field existed: the JSON simply
// doesn't have the key, so Jackson (and JSON.parse) deserialize it as absent/undefined, which
// the frontend type models as `null`.
const TRANSFER_ITEM_WITHOUT_TRADES: AdvanceResponse = {
  type: 'TRANSFER',
  date: '2026-06-01',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 0,
  seasonFinished: false,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/news?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <NewsPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('NewsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
  })

  it('renders the Transferências block for a news item that carries completed AI trades', async () => {
    vi.mocked(getNews).mockResolvedValue([TRANSFER_ITEM_WITH_TRADES])
    renderPage()
    await waitFor(() => expect(screen.getByText(/proposta\(s\) recebida\(s\)/)).toBeInTheDocument())
    expect(screen.getByText('Transferências')).toBeInTheDocument()
    expect(screen.getByText(/Zagueirão: Avaí → Joinville/)).toBeInTheDocument()
  })

  it('renders a TRANSFER news item persisted before `transfers` existed without crashing or showing the block', async () => {
    vi.mocked(getNews).mockResolvedValue([TRANSFER_ITEM_WITHOUT_TRADES])
    renderPage()
    await waitFor(() => expect(screen.getByText(/proposta\(s\) recebida\(s\)/)).toBeInTheDocument())
    expect(screen.queryByText('Transferências')).not.toBeInTheDocument()
  })
})
