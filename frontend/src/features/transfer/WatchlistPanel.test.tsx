import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import WatchlistPanel from './WatchlistPanel'
import type { WatchlistPlayer } from '../../api/types'

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

import { getWatchlist, removeFromWatchlist } from '../../api/watchlist'

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
})
