import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LiveMatchPage } from './LiveMatchPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
  changeTactics: vi.fn(),
}))
import { getLiveMatch, advanceLiveMatch, finishLiveMatch, substituteLiveMatch, changeTactics } from '../../api/liveMatch'

const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma', awayClubName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    qc,
    ...render(
      <MemoryRouter initialEntries={['/match?saveGameId=sg-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}><LiveMatchPage /></QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    ),
  }
}

describe('LiveMatchPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(advanceLiveMatch).mockResolvedValue({ ...STATE, minute: 13, status: 'IN_PROGRESS' } as never)
  })

  it('shows the score and the commentary feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/GOL! Camisa 9/)).toBeInTheDocument()
    expect(screen.getByText(/Criciúma/)).toBeInTheDocument()
  })

  it('at full time, Ver resumo finishes and navigates to the news', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    vi.mocked(finishLiveMatch).mockResolvedValue({ type: 'MATCH' } as never)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /continuar/i }))
    await waitFor(() => expect(finishLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })

  it('clears the cached live match after finishing so the top bar stops showing Retomar (regression)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    vi.mocked(finishLiveMatch).mockResolvedValue({ type: 'MATCH' } as never)
    const { qc } = renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /continuar/i }))
    await waitFor(() => expect(finishLiveMatch).toHaveBeenCalled())
    // The session is gone on the server; the cached ['liveMatch'] must be cleared to null,
    // otherwise Layout keeps rendering "Retomar partida" from stale cache.
    await waitFor(() => expect(qc.getQueryData(['liveMatch', 'sg-1'])).toBeNull())
  })

  it('opens the Tática dialog, changes mentality, clicks Continuar, and calls changeTactics', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    vi.mocked(changeTactics).mockResolvedValue({ ...STATE, currentTactics: { mentality: 'ATTACKING', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' } } as never)

    renderPage()

    // Open the tactics dialog
    fireEvent.click(await screen.findByRole('button', { name: /tática/i }))

    // The dialog should be open
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()

    // Change the mentality select to ATTACKING
    const mentalidadeSelect = screen.getByRole('combobox', { name: /mentalidade/i })
    fireEvent.change(mentalidadeSelect, { target: { value: 'ATTACKING' } })

    // Click Continuar (scoped to dialog to avoid the page-level "Continuar" button)
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    await waitFor(() =>
      expect(changeTactics).toHaveBeenCalledWith(
        'sg-1',
        expect.objectContaining({ mentality: 'ATTACKING' }),
        'test-token',
      )
    )
  })

  it('opens the sub dialog, queues a pair, confirms, and calls substituteLiveMatch', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'PAUSED_INJURY',
      onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: true, onPitch: true, used: false, eligible: true }],
      bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
      subsRemaining: 5,
    } as never)
    vi.mocked(substituteLiveMatch).mockResolvedValue({ ...STATE, subsRemaining: 4 } as never)

    renderPage()

    // Open the substitution dialog
    fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))

    // Pick the OUT player from the left column (multiple elements may contain 'Titular')
    // Click the card in the left column picker section
    const outColumn = await screen.findByText(/selecionar quem sai do campo/i)
    const outSection = outColumn.closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular/))

    // Queuing the in-progress pair should show the OUT card in the pending section
    expect(await screen.findByText(/Substituições Pendentes/i)).toBeInTheDocument()

    // Pick the IN player from the right column
    const inColumn = screen.getByText(/selecionar quem entra no jogo/i)
    const inSection = inColumn.closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva'))

    // After pairing, the pending section should now show the queued pair
    // (OUT and IN player cards both visible in the pending area)
    const pendingSection = screen.getByText(/Substituições Pendentes/i).closest('div') as HTMLElement
    expect(within(pendingSection).getByText(/Titular/)).toBeInTheDocument()

    // Click Continuar (scoped to dialog)
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    await waitFor(() =>
      expect(substituteLiveMatch).toHaveBeenCalledWith('sg-1', { outPlayerId: 'p1', inPlayerId: 'b1' }, 'test-token'))
  })

  it('auto-resumes play after a substitution made during an injury pause', async () => {
    const paused = {
      ...STATE, status: 'PAUSED_INJURY',
      onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: true, onPitch: true, used: false, eligible: true }],
      bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
      subsRemaining: 5,
    }
    vi.mocked(getLiveMatch).mockResolvedValue(paused as never)
    // The sub is recorded at committedMinute+1, so the state is STILL PAUSED_INJURY right after.
    vi.mocked(substituteLiveMatch).mockResolvedValue({ ...paused, subsRemaining: 4 } as never)

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))
    const outSection = (await screen.findByText(/selecionar quem sai do campo/i)).closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular/))
    await screen.findByText(/Substituições Pendentes/i)
    const inSection = screen.getByText(/selecionar quem entra no jogo/i).closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva'))

    // Click Continuar (scoped to dialog)
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    // Still force-paused after the sub, so the screen advances once to resume play automatically.
    await waitFor(() => expect(advanceLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })

  it('at half-time, shows the Intervalo banner and auto-opens the tactics dialog', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 45, status: 'HALF_TIME' } as never)
    renderPage()
    expect(await screen.findByText(/intervalo/i)).toBeInTheDocument()
    // The actions dialog auto-opens on the tactics tab so the manager can adjust before the 2nd half.
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(await screen.findByRole('combobox', { name: /mentalidade/i })).toBeInTheDocument()
  })

  it('queues a sub AND changes mentality, clicks Continuar, calls both substituteLiveMatch and changeTactics', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: false, onPitch: true, used: false, eligible: true }],
      bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
      subsRemaining: 5,
    } as never)
    vi.mocked(substituteLiveMatch).mockResolvedValue({ ...STATE, subsRemaining: 4 } as never)
    vi.mocked(changeTactics).mockResolvedValue({
      ...STATE, currentTactics: { mentality: 'ATTACKING', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
    } as never)

    renderPage()

    // Open the substitution dialog on tab 0
    fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))
    const dialog = await screen.findByRole('dialog')

    // Queue a substitution
    const outColumn = await screen.findByText(/selecionar quem sai do campo/i)
    const outSection = outColumn.closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular/))

    const inColumn = screen.getByText(/selecionar quem entra no jogo/i)
    const inSection = inColumn.closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva'))

    // Switch to the tactics tab
    fireEvent.click(within(dialog).getByRole('tab', { name: /instruções táticas/i }))

    // Change mentality to ATTACKING
    const mentalidadeSelect = await screen.findByRole('combobox', { name: /mentalidade/i })
    fireEvent.change(mentalidadeSelect, { target: { value: 'ATTACKING' } })

    // Click Continuar — should apply both subs and tactics
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    await waitFor(() =>
      expect(substituteLiveMatch).toHaveBeenCalledWith('sg-1', { outPlayerId: 'p1', inPlayerId: 'b1' }, 'test-token')
    )
    await waitFor(() =>
      expect(changeTactics).toHaveBeenCalledWith(
        'sg-1',
        expect.objectContaining({ mentality: 'ATTACKING' }),
        'test-token',
      )
    )
  })
})
