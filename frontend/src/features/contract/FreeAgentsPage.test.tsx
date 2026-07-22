import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FreeAgentsPage } from './FreeAgentsPage'
import * as api from '../../api/career'

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'tok' }) }))

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

function renderPage() {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
        <FreeAgentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FreeAgentsPage', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    const { getWatchlist } = await import('../../api/watchlist')
    vi.mocked(getWatchlist).mockResolvedValue([])
  })

  it('lists free agents with their wage demand', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ currentRound: 0, totalRounds: 18 } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)

    renderPage()

    expect(await screen.findByText('Livre Um')).toBeInTheDocument()
  })

  it('opens player details with a watch star when the free agent name is clicked', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    vi.spyOn(api, 'getPlayer').mockResolvedValue({
      id: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, pace: 60, finishing: 60,
      passing: 60, tackling: 60, goalkeeping: 10, stamina: 70, form: 0, morale: 70, yellowCards: 0,
      suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 0,
      value: 0, wageDemand: 100000, fitness: 100, injurySeverity: 'NONE', attributes: [],
    } as never)
    const { addToWatchlist } = await import('../../api/watchlist')
    vi.mocked(addToWatchlist).mockResolvedValue(undefined)

    renderPage()

    await userEvent.click(await screen.findByText('Livre Um'))
    const dialog = await screen.findByRole('dialog')

    const star = await within(dialog).findByRole('button', { name: 'Observar jogador' })
    fireEvent.click(star)

    await waitFor(() => expect(addToWatchlist).toHaveBeenCalledWith('S1', 'P1', 'tok'))
  })

  it('pre-fills the wage field with the grouped demand and signs with a numeric wage', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      // A fractional demand to prove the default ceils above it (clears acceptsWage).
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 68466.4 },
    ] as never)
    const signSpy = vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)

    renderPage()

    const wageField = await screen.findByLabelText<HTMLInputElement>('Salário para Livre Um')
    // Field shows the demand grouped pt-BR style, ceiled to whole reais.
    expect(wageField.value).toBe('68.467')

    await userEvent.click(screen.getByRole('button', { name: 'Contratar' }))

    // The mutation must send a finite number (the old Number("90,000.00") path sent NaN -> null).
    expect(signSpy).toHaveBeenCalledWith(
      'S1', 'P1', { wage: 68467, contractYears: 3 }, 'tok',
    )
  })

  it('parses an edited grouped wage into a plain number on submit', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    const signSpy = vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)

    renderPage()

    const wageField = await screen.findByLabelText<HTMLInputElement>('Salário para Livre Um')
    await userEvent.clear(wageField)
    await userEvent.type(wageField, '120000')
    expect(wageField.value).toBe('120.000')

    await userEvent.click(screen.getByRole('button', { name: 'Contratar' }))

    expect(signSpy).toHaveBeenCalledWith(
      'S1', 'P1', { wage: 120000, contractYears: 3 }, 'tok',
    )
  })

  it('invalidates the squad cache after signing so the roster picks up the new player', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ managedClubId: 'club-1', transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)
    const qc = new QueryClient()
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')

    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
          <FreeAgentsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Contratar' }))

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'S1', 'club-1'] }))
  })

  it('does not refetch the free-agent pool or save game on a second mount when the query client is reused', async () => {
    const save = vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    const pool = vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    const qc = new QueryClient()
    const renderOnce = () => render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
          <FreeAgentsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    const { unmount } = renderOnce()
    await screen.findByText('Livre Um')
    expect(pool).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await screen.findByText('Livre Um')
    expect(pool).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledTimes(1)
  })
})
