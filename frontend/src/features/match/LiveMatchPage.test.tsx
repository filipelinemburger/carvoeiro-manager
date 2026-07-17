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
  homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma',
  awayClubName: 'Rival Futebol Clube', awayClubShortName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
  playerRatings: [],
  opponentOnPitch: [],
  opponentTactics: { mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' },
  opponentRatings: [],
  homeClubColors: { primary: '#f7d417', secondary: '#111111' },
  awayClubColors: { primary: '#003399', secondary: '#ffffff' },
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

  it('shows both starting lineups at kickoff (minute 0) instead of the empty feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      minute: 0,
      onPitch: [
        { playerId: 'h1', name: 'Goleiro Casa', position: 'GK', overall: 60, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'GK' },
      ],
      opponentOnPitch: [
        { playerId: 'a1', name: 'Goleiro Fora', position: 'GK', overall: 58, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'GK' },
      ],
    } as never)
    renderPage()

    expect(await screen.findByText('Goleiro Casa')).toBeInTheDocument()
    expect(screen.getByText('Goleiro Fora')).toBeInTheDocument()
    expect(screen.getAllByText(STATE.homeClubShortName).length).toBeGreaterThan(0)
    expect(screen.getAllByText(STATE.awayClubShortName).length).toBeGreaterThan(0)
  })

  it('does not invert the pre-match lineup mapping when the managed club plays away', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      minute: 0,
      managedIsHome: false,
      onPitch: [
        { playerId: 'h1', name: 'Goleiro Visitante', position: 'GK', overall: 60, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'GK' },
      ],
      opponentOnPitch: [
        { playerId: 'a1', name: 'Goleiro Mandante', position: 'GK', overall: 58, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'GK' },
      ],
    } as never)
    renderPage()

    // Walks up from a player's name to the nearest ancestor whose text includes one of the
    // club short names — that ancestor is the LineupColumn for that player. The score-bar
    // header (which also renders both short names) sits outside this subtree, so it can't
    // create a false match.
    const columnFor = async (playerName: string) => {
      let el: HTMLElement | null = await screen.findByText(playerName)
      for (
        let i = 0;
        i < 10 && el && !el.textContent?.includes(STATE.homeClubShortName) && !el.textContent?.includes(STATE.awayClubShortName);
        i++
      ) {
        el = el.parentElement
      }
      if (!el) throw new Error(`Could not locate the lineup column for "${playerName}"`)
      return el
    }

    // Managed club plays away, so the managed player (onPitch) must land in the column
    // headed by the away club's short name, not the home one — proves the mapping is not inverted.
    const managedColumn = await columnFor('Goleiro Visitante')
    expect(within(managedColumn).getByText(STATE.awayClubShortName)).toBeInTheDocument()
    expect(within(managedColumn).queryByText(STATE.homeClubShortName)).not.toBeInTheDocument()

    // The AI opponent, playing at home, must land in the home column.
    const opponentColumn = await columnFor('Goleiro Mandante')
    expect(within(opponentColumn).getByText(STATE.homeClubShortName)).toBeInTheDocument()
    expect(within(opponentColumn).queryByText(STATE.awayClubShortName)).not.toBeInTheDocument()
  })

  it('shows the score and the commentary feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/GOL! Camisa 9/)).toBeInTheDocument()
    expect(screen.getByText(/Criciúma/)).toBeInTheDocument()
  })

  it('renders the short club name, not the full legal name, in the header', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/Criciúma\b/)).toBeInTheDocument()
    expect(screen.queryByText(/Criciúma Esporte Clube/)).not.toBeInTheDocument()
  })

  it('at full time, Ver resumo finishes and navigates to the news', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    vi.mocked(finishLiveMatch).mockResolvedValue({ type: 'MATCH' } as never)
    const { qc } = renderPage()
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    fireEvent.click(await screen.findByRole('button', { name: /continuar/i }))
    await waitFor(() => expect(finishLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['finances'] })
  })

  it('at full time, auto-switches to the Atuações tab and shows final grades', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, minute: 90, status: 'FULL_TIME',
      playerRatings: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', minutesPlayed: 90, goals: 1, rating: 7.6 },
        { playerId: 'p2', name: 'Zagueiro', position: 'DEF', minutesPlayed: 90, goals: 0, rating: 6.1 },
      ],
    } as never)
    renderPage()
    // The Atuações tab is auto-selected at full time, so the grades render without a click.
    expect(await screen.findByText('Camisa 9')).toBeInTheDocument()
    expect(screen.getByText('7.6')).toBeInTheDocument()
    expect(screen.getByText('Zagueiro')).toBeInTheDocument()
  })

  it('shows live player grades in the Atuações tab during the game', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      playerRatings: [
        { playerId: 'px', name: 'Meia Vivo', position: 'MID', minutesPlayed: 20, goals: 0, rating: 6.7 },
      ],
    } as never)
    renderPage()
    // Default tab is Lances (the feed); the grades only show after switching to Atuações.
    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    expect(await screen.findByText('Meia Vivo')).toBeInTheDocument()
    expect(screen.getByText('6.7')).toBeInTheDocument()
  })

  it('shows a hint in each column when no grades exist yet', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', playerRatings: [], opponentRatings: [] } as never)
    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    expect(await screen.findAllByText(/atuações aparecem conforme/i)).toHaveLength(2)
  })

  it('shows both teams\' ratings simultaneously in two columns, no toggle needed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      playerRatings: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', minutesPlayed: 12, goals: 1, rating: 7.0 },
      ],
      opponentRatings: [
        { playerId: 'o1', name: 'Rival Camisa 10', position: 'MID', minutesPlayed: 12, goals: 0, rating: 6.2 },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    expect(await screen.findByText('Camisa 9')).toBeInTheDocument()
    expect(screen.getByText('Rival Camisa 10')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^meu time$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^adversário$/i })).not.toBeInTheDocument()
  })

  it('dims a player who has been substituted off in the ratings list', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      onPitch: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', overall: 70, fitness: 80, hobbled: false, onPitch: true, used: false, eligible: true, role: 'ST' },
      ],
      playerRatings: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', minutesPlayed: 90, goals: 1, rating: 7.0 },
        { playerId: 'p2', name: 'Saiu Cedo', position: 'MID', minutesPlayed: 45, goals: 0, rating: 6.0 },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    const stillPlayingRow = (await screen.findByText('Camisa 9')).closest('div')
    const subbedOffRow = (await screen.findByText('Saiu Cedo')).closest('div')

    expect(stillPlayingRow?.style.opacity).toBe('')
    expect(subbedOffRow?.style.opacity).toBe('0.5')
  })

  it('shows both teams on the Campo tab with their names and an injury badge', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      onPitch: [
        { playerId: 'p1', name: 'Goleiro Titular', position: 'GK', overall: 70, fitness: 100,
          hobbled: false, onPitch: true, used: false, eligible: true, role: 'GOALKEEPER' },
      ],
      opponentOnPitch: [
        { playerId: 'o1', name: 'Zagueiro Rival', position: 'DEF', overall: 65, fitness: 100,
          hobbled: true, onPitch: true, used: false, eligible: true, role: 'STOPPER' },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /campo/i }))
    expect(await screen.findByTestId('pitch')).toBeInTheDocument()
    expect(await screen.findByText('Goleiro Titular')).toBeInTheDocument()
    expect(screen.getByText('Zagueiro Rival')).toBeInTheDocument()
    expect(screen.getByLabelText('Lesionado')).toBeInTheDocument()
  })

  it('does not invert the Campo mapping when the managed club plays away', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      managedIsHome: false,
      onPitch: [
        { playerId: 'p1', name: 'Managed Titular', position: 'MID', overall: 70, fitness: 100,
          hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX' },
      ],
      opponentOnPitch: [
        { playerId: 'o1', name: 'Home Rival Titular', position: 'MID', overall: 65, fitness: 100,
          hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX' },
      ],
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /campo/i }))

    // Managed club plays away, so the managed player must render with awayClubColors
    // (#003399 -> rgb(0, 51, 153)), not homeClubColors — proves the mapping is not inverted.
    const managedToken = (await screen.findByText('Managed Titular')).closest('[data-testid="player-token"]') as HTMLElement
    expect(managedToken.querySelector('[data-role="disc"]')).toHaveStyle({ backgroundColor: 'rgb(0, 51, 153)' })

    // The opponent, playing at home, must render with homeClubColors (#f7d417 -> rgb(247, 212, 23)).
    const opponentToken = (await screen.findByText('Home Rival Titular')).closest('[data-testid="player-token"]') as HTMLElement
    expect(opponentToken.querySelector('[data-role="disc"]')).toHaveStyle({ backgroundColor: 'rgb(247, 212, 23)' })
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
      expect(substituteLiveMatch).toHaveBeenCalledWith(
        'sg-1',
        { subs: [{ outPlayerId: 'p1', inPlayerId: 'b1' }] },
        'test-token',
      ))
    expect(substituteLiveMatch).toHaveBeenCalledTimes(1)
  })

  it('queues TWO substitutions and applies them in a single batched substituteLiveMatch call', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      onPitch: [
        { playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: false, onPitch: true, used: false, eligible: true },
        { playerId: 'p2', name: 'Titular Dois', position: 'DEF', overall: 72, fitness: 60, hobbled: false, onPitch: true, used: false, eligible: true },
      ],
      bench: [
        { playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true },
        { playerId: 'b2', name: 'Reserva Dois', position: 'DEF', overall: 68, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true },
      ],
      subsRemaining: 5,
    } as never)
    vi.mocked(substituteLiveMatch).mockResolvedValue({ ...STATE, subsRemaining: 3 } as never)

    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))
    const dialog = await screen.findByRole('dialog')

    // Queue the first pair
    let outSection = (await screen.findByText(/selecionar quem sai do campo/i)).closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular$/))
    let inSection = screen.getByText(/selecionar quem entra no jogo/i).closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva'))

    // Queue the second pair
    outSection = (await screen.findByText(/selecionar quem sai do campo/i)).closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular Dois/))
    inSection = screen.getByText(/selecionar quem entra no jogo/i).closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva Dois'))

    // Click Continuar once — both pairs must go out in a single batched request
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    await waitFor(() =>
      expect(substituteLiveMatch).toHaveBeenCalledWith(
        'sg-1',
        { subs: [{ outPlayerId: 'p1', inPlayerId: 'b1' }, { outPlayerId: 'p2', inPlayerId: 'b2' }] },
        'test-token',
      ))
    expect(substituteLiveMatch).toHaveBeenCalledTimes(1)
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
      expect(substituteLiveMatch).toHaveBeenCalledWith(
        'sg-1',
        { subs: [{ outPlayerId: 'p1', inPlayerId: 'b1' }] },
        'test-token',
      )
    )
    expect(substituteLiveMatch).toHaveBeenCalledTimes(1)
    await waitFor(() =>
      expect(changeTactics).toHaveBeenCalledWith(
        'sg-1',
        expect.objectContaining({ mentality: 'ATTACKING' }),
        'test-token',
      )
    )
  })
})
