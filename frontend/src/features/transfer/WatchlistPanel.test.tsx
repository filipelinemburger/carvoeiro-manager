import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import WatchlistPanel from './WatchlistPanel'
import type { WatchlistPlayer } from '../../api/types'

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

vi.mock('../../api/career', () => ({
  getPlayer: vi.fn(),
}))

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))

import { getWatchlist, removeFromWatchlist } from '../../api/watchlist'
import { getPlayer } from '../../api/career'

const WATCHED_UM: WatchlistPlayer = {
  playerId: 'p1', name: 'Jogador Um', position: 'ATT', age: 24, overall: 78,
  clubName: 'Rival FC', freeAgent: false, transferListed: true, preContractEligible: false,
}

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <WatchlistPanel saveGameId="sg-1" token="test-token" />
    </QueryClientProvider>,
  )
}

describe('WatchlistPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders watched rows with club name and status chips', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])

    renderPanel()

    await screen.findByText('Jogador Um')
    expect(screen.getByText('Rival FC')).toBeInTheDocument()
    expect(screen.getByText('Listado')).toBeInTheDocument()
    expect(screen.queryByText('Pré-contrato')).not.toBeInTheDocument()
  })

  it('shows the empty-state message when the watchlist is empty', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])

    renderPanel()

    expect(await screen.findByText(/Nenhum jogador observado/i)).toBeInTheDocument()
  })

  it('calls removeFromWatchlist with the player id when Remover is clicked', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])
    vi.mocked(removeFromWatchlist).mockResolvedValue(undefined)

    renderPanel()
    await screen.findByText('Jogador Um')

    fireEvent.click(screen.getByRole('button', { name: 'Remover' }))

    await waitFor(() =>
      expect(removeFromWatchlist).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'),
    )
  })

  it('opens player details with a filled watch star when the row is clicked, and keeps the Remover button', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])
    vi.mocked(getPlayer).mockResolvedValue({
      id: 'p1', name: 'Jogador Um', position: 'ATT', age: 24, overall: 78, pace: 60, finishing: 60,
      passing: 60, tackling: 60, goalkeeping: 10, stamina: 70, form: 0, morale: 70, yellowCards: 0,
      suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 1,
      value: 0, wageDemand: 0, fitness: 100, injurySeverity: 'NONE', attributes: [],
    } as never)

    renderPanel()
    fireEvent.click(await screen.findByText('Jogador Um'))

    const dialog = await screen.findByRole('dialog')
    expect(getPlayer).toHaveBeenCalledWith('sg-1', 'p1', 'test-token')

    // Already watched, so the star renders filled (removes on click, exercised in WatchStar's
    // own unit tests) — and the row's separate Remover button still coexists underneath (MUI
    // marks the rest of the page aria-hidden while the modal is open, hence `hidden: true`).
    expect(await within(dialog).findByRole('button', { name: 'Remover da observação' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remover', hidden: true })).toBeInTheDocument()
  })

  it('does not render a row for a watchlist entry missing a name (transient optimistic placeholder)', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([
      WATCHED_UM,
      { playerId: 'p2' } as never,
    ])

    renderPanel()

    await screen.findByText('Jogador Um')
    expect(screen.queryByText('p2')).not.toBeInTheDocument()
    // Only the well-formed row renders.
    expect(screen.getAllByRole('row')).toHaveLength(2) // header + 1 data row
  })
})
