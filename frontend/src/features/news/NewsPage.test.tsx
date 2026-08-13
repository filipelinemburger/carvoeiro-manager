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

// Variant that exposes the QueryClient so a test can drive a background refetch via
// invalidateQueries, mirroring what onSuccess of the "Avançar" mutation does on the real
// news list ['news', saveGameId].
function renderPageWithClient() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const utils = render(
    <MemoryRouter initialEntries={['/news?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <NewsPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
  return { qc, ...utils }
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

  it('lists derived headlines and auto-selects the oldest unread, showing its detail card + marking it read', async () => {
    renderPage()
    // Both headlines appear in the list.
    await waitFor(() => expect(screen.getByText('Rodada 12 — resultados')).toBeInTheDocument())
    expect(screen.getByText('Janela de transferências — 3 proposta(s)')).toBeInTheDocument()
    // ITEMS is newest-first ([n1, n2]) and both are unread, so the oldest unread — n2
    // (TRANSFER) — is auto-selected: its detail card header appears.
    await waitFor(() => expect(screen.getByText('Janela de Transferências')).toBeInTheDocument())
    // n2 was unread → marked read on landing.
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n2', 'test-token'))
  })

  it('selecting a headline swaps the detail card and marks that item read', async () => {
    renderPage()
    // Auto-lands on n2 (the oldest unread, TRANSFER); click the other headline to swap to n1.
    const matchRow = await screen.findByText('Rodada 12 — resultados')
    fireEvent.click(matchRow)
    // MATCH detail card header appears (round + 1, see AdvanceEventCard's roundLabel).
    await waitFor(() => expect(screen.getByText(/Série B · Rodada 12/)).toBeInTheDocument())
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n1', 'test-token'))
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

  it('re-selects the oldest unread item when a fresh one is prepended by a background refetch, even while the previous selection is still present (cached-advance path)', async () => {
    const A: NewsFeedItem = { id: 'a1', read: false, payload: match(10) }
    const B: NewsFeedItem = { id: 'b1', read: false, payload: transfer(5) }
    vi.mocked(getNews)
      .mockResolvedValueOnce({ content: [A], page: 1, size: 10, totalElements: 1, totalPages: 1 })
      // The server-truth refetch reflects that A was already marked read by the earlier
      // landing (below) — only the freshly-prepended B is still unread.
      .mockResolvedValue({ content: [B, { ...A, read: true }], page: 1, size: 10, totalElements: 2, totalPages: 1 })

    const { qc } = renderPageWithClient()

    // A (the only item at first) is auto-selected and marked read.
    // AdvanceEventCard's header shows `round + 1` (the completed round), so match(10) reads
    // "Rodada 11" — see AdvanceEventCard.tsx's roundLabel.
    await waitFor(() => expect(screen.getByText(/Série B · Rodada 11/)).toBeInTheDocument())
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'a1', 'test-token'))

    // Simulate the "Avançar" cached-advance path: the news list query is invalidated (as
    // advanceMutation.onSuccess does) and its background refetch resolves to [B, A] — a
    // strictly-newer item B prepended — while the current selection A is still present.
    await qc.invalidateQueries({ queryKey: ['news', 'sg-1'] })

    // B must become the selection (it's the only unread item left): its detail card renders
    // and it gets marked read. On the old effect (which only re-selects when the current
    // selection drops OUT of the list) this never happens, because A remains present — B is
    // left unopened.
    await waitFor(() => expect(screen.getByText('Janela de Transferências')).toBeInTheDocument())
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'b1', 'test-token'))
  })

  it('lands on the oldest unread item, not the newest', async () => {
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
    vi.mocked(getNews).mockResolvedValue({
      content: [
        { id: 'n3', read: false, payload: match(13) },
        { id: 'n2', read: false, payload: match(12) },
        { id: 'n1', read: true, payload: match(11) },
      ],
      page: 1, size: 10, totalElements: 3, totalPages: 1,
    })
    vi.mocked(markNewsRead).mockResolvedValue(undefined)
    renderPage()
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n2', expect.anything()))
  })

  it('lands on the newest item when everything is already read', async () => {
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
    vi.mocked(getNews).mockResolvedValue({
      content: [
        { id: 'n3', read: true, payload: match(13) },
        { id: 'n2', read: true, payload: match(12) },
      ],
      page: 1, size: 10, totalElements: 2, totalPages: 1,
    })
    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (0)' })).toBeDisabled())
    expect(markNewsRead).not.toHaveBeenCalled()
  })

  it('"Ler próxima" walks forward to the next unread item and marks it read', async () => {
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_GAME)
    vi.mocked(getNews).mockResolvedValue({
      content: [
        { id: 'n3', read: false, payload: match(13) },
        { id: 'n2', read: false, payload: match(12) },
      ],
      page: 1, size: 10, totalElements: 2, totalPages: 1,
    })
    vi.mocked(markNewsRead).mockResolvedValue(undefined)
    renderPage()
    // Landed on n2 (the oldest unread), leaving exactly one unread.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (1)' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Ler próxima (1)' }))
    await waitFor(() => expect(markNewsRead).toHaveBeenCalledWith('sg-1', 'n3', expect.anything()))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ler próxima (0)' })).toBeDisabled())
  })
})
