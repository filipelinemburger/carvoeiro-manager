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

  it('"Carregar mais" fetches the next page and hides once exhausted', async () => {
    const page1Items: NewsFeedItem[] = [
      { id: 'p1-1', read: false, payload: match(30) },
      { id: 'p1-2', read: false, payload: match(29) },
    ]
    const page2Items: NewsFeedItem[] = [
      { id: 'p2-1', read: false, payload: transfer(7) },
    ]
    vi.mocked(getNews).mockImplementation((_saveGameId, page) =>
      Promise.resolve(
        page === 1
          ? { content: page1Items, page: 1, size: 10, totalElements: 3, totalPages: 2 }
          : { content: page2Items, page: 2, size: 10, totalElements: 3, totalPages: 2 },
      ),
    )

    renderPage()

    // Page 1 headlines load first.
    await waitFor(() => expect(screen.getByText('Rodada 31 — resultados')).toBeInTheDocument())
    expect(screen.getByText('Rodada 30 — resultados')).toBeInTheDocument()

    const loadMoreButton = await screen.findByRole('button', { name: /carregar mais/i })
    fireEvent.click(loadMoreButton)

    // Page 2's headline appears, fetched with the right page number.
    await waitFor(() => expect(screen.getByText('Janela de transferências — 7 proposta(s)')).toBeInTheDocument())
    expect(getNews).toHaveBeenCalledWith('sg-1', 2, 10, 'test-token')

    // Exhausted (page 2 of 2): the button disappears.
    await waitFor(() => expect(screen.queryByRole('button', { name: /carregar mais/i })).not.toBeInTheDocument())
  })
})
