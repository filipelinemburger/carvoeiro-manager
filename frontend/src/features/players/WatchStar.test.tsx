import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { WatchStar } from './WatchStar'
import { AuthProvider } from '../../auth/AuthContext'
import type { WatchlistPlayer } from '../../api/types'

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

import { getWatchlist, addToWatchlist, removeFromWatchlist } from '../../api/watchlist'

const WATCHED_UM: WatchlistPlayer = {
  playerId: 'p1', name: 'Jogador Um', position: 'ATT', age: 24, overall: 78,
  clubName: 'Rival FC', freeAgent: false, transferListed: true, preContractEligible: false,
}

function renderStar(playerId = 'p1', qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(
    <AuthProvider>
      <QueryClientProvider client={qc}>
        <WatchStar saveGameId="sg-1" playerId={playerId} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

describe('WatchStar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders nothing while the watchlist is loading', async () => {
    vi.mocked(getWatchlist).mockReturnValue(new Promise(() => {}))
    renderStar()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the unfilled star and calls addToWatchlist when the player is not watched', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])
    vi.mocked(addToWatchlist).mockResolvedValue(undefined)

    renderStar()
    expect(await screen.findByTestId('StarBorderIcon')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Observar jogador' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Observar jogador' }))

    await waitFor(() => expect(addToWatchlist).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('flips to the filled star immediately (optimistic), before the request resolves', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])
    let resolveAdd!: () => void
    vi.mocked(addToWatchlist).mockReturnValue(new Promise(resolve => { resolveAdd = () => resolve(undefined) }))

    renderStar()
    await screen.findByTestId('StarBorderIcon')

    fireEvent.click(screen.getByRole('button', { name: 'Observar jogador' }))

    // Flipped before the mocked request has resolved.
    await waitFor(() => expect(screen.getByTestId('StarIcon')).toBeInTheDocument())

    resolveAdd()
  })

  it('shows the filled star and calls removeFromWatchlist when the player is watched', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])
    vi.mocked(removeFromWatchlist).mockResolvedValue(undefined)

    renderStar()
    expect(await screen.findByTestId('StarIcon')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remover da observação' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Remover da observação' }))

    await waitFor(() => expect(removeFromWatchlist).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('flips to the unfilled star immediately (optimistic) on remove, before the request resolves', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])
    let resolveRemove!: () => void
    vi.mocked(removeFromWatchlist).mockReturnValue(new Promise(resolve => { resolveRemove = () => resolve(undefined) }))

    renderStar()
    await screen.findByTestId('StarIcon')

    fireEvent.click(screen.getByRole('button', { name: 'Remover da observação' }))

    await waitFor(() => expect(screen.getByTestId('StarBorderIcon')).toBeInTheDocument())

    resolveRemove()
  })

  it('rolls back to the unfilled star if adding to the watchlist fails', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])
    let rejectAdd!: (e: Error) => void
    vi.mocked(addToWatchlist).mockReturnValue(new Promise((_, reject) => { rejectAdd = reject }))

    renderStar()
    await screen.findByTestId('StarBorderIcon')

    fireEvent.click(screen.getByRole('button', { name: 'Observar jogador' }))
    await waitFor(() => expect(screen.getByTestId('StarIcon')).toBeInTheDocument())

    rejectAdd(new Error('network'))

    await waitFor(() => expect(screen.getByTestId('StarBorderIcon')).toBeInTheDocument())
  })

  it('rolls back to the filled star if removing from the watchlist fails', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([WATCHED_UM])
    let rejectRemove!: (e: Error) => void
    vi.mocked(removeFromWatchlist).mockReturnValue(new Promise((_, reject) => { rejectRemove = reject }))

    renderStar()
    await screen.findByTestId('StarIcon')

    fireEvent.click(screen.getByRole('button', { name: 'Remover da observação' }))
    await waitFor(() => expect(screen.getByTestId('StarBorderIcon')).toBeInTheDocument())

    rejectRemove(new Error('network'))

    await waitFor(() => expect(screen.getByTestId('StarIcon')).toBeInTheDocument())
  })

  it('disables the button while a toggle is pending', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])
    vi.mocked(addToWatchlist).mockReturnValue(new Promise(() => {}))

    renderStar()
    await screen.findByTestId('StarBorderIcon')

    fireEvent.click(screen.getByRole('button', { name: 'Observar jogador' }))

    await waitFor(() => expect(screen.getByRole('button')).toBeDisabled())
  })

  it('shares the watchlist cache entry across two WatchStar instances for different players', async () => {
    vi.mocked(getWatchlist).mockResolvedValue([])
    vi.mocked(addToWatchlist).mockResolvedValue(undefined)
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <WatchStar saveGameId="sg-1" playerId="p1" />
          <WatchStar saveGameId="sg-1" playerId="p2" />
        </QueryClientProvider>
      </AuthProvider>,
    )

    await waitFor(() => expect(getWatchlist).toHaveBeenCalledTimes(1))
  })
})
