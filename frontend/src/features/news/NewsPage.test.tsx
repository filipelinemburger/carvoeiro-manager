import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NewsPage } from './NewsPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { AdvanceResponse, NewsFeedItem, SaveGameResponse } from '../../api/types'

vi.mock('../../api/career', () => ({ getSaveGame: vi.fn() }))
vi.mock('../../api/news', () => ({
  getNews: vi.fn(),
  markNewsRead: vi.fn(),
  markAllNewsRead: vi.fn(),
}))

import { getSaveGame } from '../../api/career'
import { getNews, markNewsRead, markAllNewsRead } from '../../api/news'

const SAVE_GAME: SaveGameResponse = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'comp-1', competitionName: 'Série B',
  managedClubId: 'club-1', managedClubName: 'Criciúma EC', currentRound: 12, totalRounds: 38,
}

function match(round: number): AdvanceResponse {
  return {
    type: 'MATCH', date: '2026-07-21', competitionName: 'Série B', round,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: 0, seasonFinished: false,
  }
}
function transfer(bids: number): AdvanceResponse {
  return {
    type: 'TRANSFER', date: '2026-07-18', competitionName: null, round: null,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: bids, seasonFinished: false,
  }
}

const ITEMS: NewsFeedItem[] = [
  { id: 'n1', read: false, payload: match(11) },
  { id: 'n2', read: false, payload: transfer(3) },
]

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

describe('NewsPage (master-detail)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
    vi.mocked(getNews).mockResolvedValue({ content: ITEMS, page: 1, size: 10, totalElements: 2, totalPages: 1 })
    vi.mocked(markNewsRead).mockResolvedValue()
    vi.mocked(markAllNewsRead).mockResolvedValue()
  })

  it('lists derived headlines and auto-selects the newest, showing its detail card + marking it read', async () => {
    renderPage()
    // Both headlines appear in the list.
    await waitFor(() => expect(screen.getByText('Rodada 12 — resultados')).toBeInTheDocument())
    expect(screen.getByText('Janela de transferências — 3 proposta(s)')).toBeInTheDocument()
    // Newest (MATCH) is auto-selected: its detail card header shows the round label.
    await waitFor(() => expect(screen.getByText(/Série B · Rodada 12/)).toBeInTheDocument())
    // Newest was unread → marked read on landing.
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n1', 'test-token'))
  })

  it('selecting a headline swaps the detail card and marks that item read', async () => {
    renderPage()
    const transferRow = await screen.findByText('Janela de transferências — 3 proposta(s)')
    fireEvent.click(transferRow)
    // TRANSFER detail card header appears.
    await waitFor(() => expect(screen.getByText('Janela de Transferências')).toBeInTheDocument())
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n2', 'test-token'))
  })

  it('"Marcar todas como lidas" marks all read', async () => {
    renderPage()
    const button = await screen.findByRole('button', { name: /marcar todas como lidas/i })
    fireEvent.click(button)
    await waitFor(() => expect(markAllNewsRead).toHaveBeenCalledWith('sg-1', 'test-token'))
  })
})
