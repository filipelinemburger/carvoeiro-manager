import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LiveMatchPage } from './LiveMatchPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  advanceToLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
  changeTactics: vi.fn(),
}))
import {
  getLiveMatch, advanceLiveMatch, advanceToLiveMatch, finishLiveMatch, substituteLiveMatch, changeTactics,
} from '../../api/liveMatch'

vi.mock('../../api/career', () => ({ getPlayer: vi.fn(), getStandings: vi.fn(), getSaveGame: vi.fn() }))
import { getPlayer, getStandings, getSaveGame } from '../../api/career'

// WatchStar (mounted for opponent-side players in the read-only PlayerDetails modal) owns the
// watchlist query itself — stub it out here so the modal tests don't need a real watchlist.
vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))
import { getWatchlist } from '../../api/watchlist'

const MOCK_PLAYER = {
  id: 'p1', name: 'Camisa 9', position: 'ATT', age: 24, overall: 78, pace: 70, finishing: 80,
  passing: 65, tackling: 30, goalkeeping: 5, stamina: 75, form: 1, morale: 80, yellowCards: 0,
  suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 2,
  value: 1_000_000, wageDemand: 20_000, fitness: 90, injurySeverity: 'NONE', attributes: [],
}

const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  stadiumName: 'Estádio Heriberto Hülse',
  homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma',
  awayClubName: 'Rival Futebol Clube', awayClubShortName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
  currentTactics: { mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
  playerRatings: [],
  opponentOnPitch: [],
  opponentTactics: { mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' },
  opponentRatings: [],
  homeClubColors: { primary: '#f7d417', secondary: '#111111' },
  awayClubColors: { primary: '#003399', secondary: '#ffffff' },
  competitionId: 'comp-1', parallelScores: [],
  // Every counter 0, possession 50/50 — exactly what minute 0 looks like. A real, type-checked
  // fixture here (not a fallback the component conjures on `stats == null`) is what actually
  // exercises MatchStatsTab's real data path once it's mounted in this page.
  stats: {
    home: { possession: 50, shots: 0, shotsOnTarget: 0, saves: 0, corners: 0, offsides: 0, fouls: 0, yellowCards: 0, redCards: 0 },
    away: { possession: 50, shots: 0, shotsOnTarget: 0, saves: 0, corners: 0, offsides: 0, fouls: 0, yellowCards: 0, redCards: 0 },
  },
}

// The Escalações tab renders each player name twice — once as a clickable list Link (rendered
// as an MUI `<button>`), once as a plain, non-interactive PlayerToken label on the pitch. Picks
// the clickable one out of a `getAllByText`/`findAllByText` match set.
function listOccurrence(matches: HTMLElement[]): HTMLElement {
  const el = matches.find(m => m.closest('button'))
  if (!el) throw new Error('No clickable list occurrence found among the matches')
  return el
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
    vi.mocked(getWatchlist).mockResolvedValue([])
    // Safe defaults for the two new live-match tabs so every existing test keeps a resolved
    // (never-undefined) query; individual tab tests override these.
    vi.mocked(getStandings).mockResolvedValue([])
    vi.mocked(getSaveGame).mockResolvedValue({ managedClubId: 'club-x' } as never)
  })

  it('shows both starting lineups in the Escalações tab at kickoff (minute 0)', async () => {
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

    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))

    // Each name renders twice — once in the list, once as a PlayerToken on the pitch.
    expect(await screen.findAllByText('Goleiro Casa')).toHaveLength(2)
    expect(screen.getAllByText('Goleiro Fora')).toHaveLength(2)
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

    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))

    // The scoreboard's "own" card must follow managedIsHome too, not assume home — proves
    // MatchScoreboard routes own/opponent correctly when the managed club plays away.
    expect(await screen.findByTestId('club-card-own')).toHaveTextContent(STATE.awayClubShortName)

    // Walks up from a player's name to the nearest ancestor whose text includes one of the
    // club short names — that ancestor is the LineupColumn for that player. The score-bar
    // header (which also renders both short names) sits outside this subtree, so it can't
    // create a false match. Each name renders twice (list + pitch token); start from the
    // clickable list occurrence (an MUI Link rendered as a <button>) — the token has no such
    // ancestor and its own walk-up would land on the shared grid wrapper (both columns'
    // short names), producing a false match.
    const columnFor = async (playerName: string) => {
      const matches = await screen.findAllByText(playerName)
      let el: HTMLElement | null = listOccurrence(matches)
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

  it('shows the score and the latest narration', async () => {
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

  it('shows the match date in the header', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    // STATE.matchDate is '2026-03-01' -> pt-BR formatted as 01/03/2026.
    expect(await screen.findByText(/01\/03\/2026/)).toBeInTheDocument()
  })

  it('renders without the match date when matchDate is null (null-safe)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, matchDate: null } as never)
    renderPage()
    expect(await screen.findByText(/GOL! Camisa 9/)).toBeInTheDocument()
    expect(screen.queryByText(/\d{2}\/\d{2}\/\d{4}/)).not.toBeInTheDocument()
  })

  it('shows the stadium name before the date in the header', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/Estádio Heriberto Hülse · 01\/03\/2026/)).toBeInTheDocument()
  })

  it('renders the date without a stadium prefix when stadiumName is missing (null-safe)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, stadiumName: null } as never)
    renderPage()
    expect(await screen.findByText(/01\/03\/2026/)).toBeInTheDocument()
    expect(screen.queryByText(/Estádio/)).not.toBeInTheDocument()
  })

  it('shows the weather chip and the crowd size when the backend supplies them', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, weather: 'ENSOLARADO', temperatureC: 27, attendance: 19300,
    } as never)
    renderPage()
    await screen.findByText(/GOL! Camisa 9/)
    expect(screen.getByTestId('weather-chip')).toBeInTheDocument()
    expect(screen.getByText(/Ensolarado · 27°C/)).toBeInTheDocument()
    expect(screen.getByText('Público: 19.300')).toBeInTheDocument()
  })

  it('hides the weather chip and the crowd size before they are revealed (null-safe)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, weather: null, temperatureC: null, attendance: null,
    } as never)
    renderPage()
    await screen.findByText(/GOL! Camisa 9/)
    expect(screen.queryByTestId('weather-chip')).not.toBeInTheDocument()
    expect(screen.queryByText(/Público:/)).not.toBeInTheDocument()
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

  it('shows both teams on the Escalações tab with their names and an injury badge', async () => {
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

    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))
    expect(await screen.findByTestId('pitch')).toBeInTheDocument()
    expect(await screen.findAllByText('Goleiro Titular')).toHaveLength(2)
    expect(screen.getAllByText('Zagueiro Rival')).toHaveLength(2)
    expect(screen.getByLabelText('Lesionado')).toBeInTheDocument()
  })

  it('does not invert the Escalações mapping when the managed club plays away', async () => {
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

    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))
    await screen.findByTestId('pitch')

    // The player's name renders twice (list + pitch token); select the token specifically —
    // the list copy has no `player-token` ancestor, so it can't create a false match.
    const tokenFor = (playerName: string): HTMLElement => {
      const token = Array.from(document.querySelectorAll('[data-testid="player-token"]'))
        .find(el => el.textContent?.includes(playerName))
      if (!token) throw new Error(`Could not locate the player token for "${playerName}"`)
      return token as HTMLElement
    }

    // Managed club plays away, so the managed player must render with awayClubColors
    // (#003399 -> rgb(0, 51, 153)), not homeClubColors — proves the mapping is not inverted.
    const managedToken = tokenFor('Managed Titular')
    expect(managedToken.querySelector('[data-role="disc"]')).toHaveStyle({ backgroundColor: 'rgb(0, 51, 153)' })

    // The opponent, playing at home, must render with homeClubColors (#f7d417 -> rgb(247, 212, 23)).
    const opponentToken = tokenFor('Home Rival Titular')
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

  it('clicking "Avançar até o fim" calls advanceToLiveMatch once with FULL_TIME, blocks the per-minute clock while in flight, and renders the returned state', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', minute: 60 } as never)
    let resolveAdvanceTo: (state: never) => void = () => {}
    vi.mocked(advanceToLiveMatch).mockImplementation(
      () => new Promise(resolve => { resolveAdvanceTo = resolve }),
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: /avançar até o fim/i }))

    await waitFor(() => expect(advanceToLiveMatch).toHaveBeenCalledWith('sg-1', 'FULL_TIME', 'test-token'))
    // A second click while the skip is in flight must not double-fire the request.
    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: /avançar até o fim/i }))

    // The skip is still pending (mock promise unresolved). The client-owned clock ticks every
    // 1.5s — wait past that to prove it does NOT sneak in a per-minute advance meanwhile.
    await new Promise(resolve => setTimeout(resolve, 2000))
    expect(advanceLiveMatch).not.toHaveBeenCalled()
    expect(advanceToLiveMatch).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveAdvanceTo({ ...STATE, status: 'FULL_TIME', minute: 90 } as never)
      await Promise.resolve()
    })

    // The finish flow ("Continuar") takes over at FULL_TIME instead of auto-finishing.
    expect(await screen.findByRole('button', { name: /^continuar$/i })).toBeInTheDocument()
    expect(finishLiveMatch).not.toHaveBeenCalled()
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  }, 10_000)

  it('clicking "Avançar até o intervalo" calls advanceToLiveMatch once with HALF_TIME, blocks the per-minute clock while in flight, and hands off to the half-time flow', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', minute: 10 } as never)
    let resolveAdvanceTo: (state: never) => void = () => {}
    vi.mocked(advanceToLiveMatch).mockImplementation(
      () => new Promise(resolve => { resolveAdvanceTo = resolve }),
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: /avançar até o intervalo/i }))

    await waitFor(() => expect(advanceToLiveMatch).toHaveBeenCalledWith('sg-1', 'HALF_TIME', 'test-token'))

    await new Promise(resolve => setTimeout(resolve, 2000))
    expect(advanceLiveMatch).not.toHaveBeenCalled()
    expect(advanceToLiveMatch).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveAdvanceTo({ ...STATE, status: 'HALF_TIME', minute: 45 } as never)
      await Promise.resolve()
    })

    // The existing half-time flow takes over: banner + the "Iniciar 2º tempo" button on the
    // match card (same assertions as the pre-existing "at half-time" test — no dialog
    // auto-opens anymore, so the button is directly queryable here).
    expect(await screen.findByText(/intervalo/i)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /iniciar 2º tempo/i })).toBeInTheDocument()
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  }, 10_000)

  it('guards against a per-minute advance racing an in-flight skip (regression: both write the same cache key)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'PAUSED_INJURY', minute: 60 } as never)
    // Never resolves — simulates the per-minute advance staying in flight for the whole test.
    vi.mocked(advanceLiveMatch).mockImplementation(() => new Promise(() => {}))
    renderPage()

    const resumeButton = await screen.findByRole('button', { name: /continuar assim mesmo/i })
    fireEvent.click(resumeButton)
    await waitFor(() => expect(advanceLiveMatch).toHaveBeenCalledTimes(1))

    // While the per-minute advance is in flight, both the "Continuar assim mesmo" button
    // (which would fire another advanceLiveMatch) and the skip item (which would fire
    // advanceToLiveMatch against the same ['liveMatch', saveGameId] cache key) must be disabled.
    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    const skipItem = await screen.findByRole('menuitem', { name: /avançar até o fim/i })
    // MenuItem renders as an <li>, not a native <button> — jest-dom's toBeDisabled() only
    // recognises form-control tags, so the MUI-applied aria-disabled attribute is the correct
    // signal here (same guard state the old toBeDisabled() check on the <button> asserted).
    await waitFor(() => expect(skipItem).toHaveAttribute('aria-disabled', 'true'))
    expect(resumeButton).toBeDisabled()

    // Attempting to click the (disabled) skip item must not sneak an advanceToLiveMatch call
    // through while advanceMut is still pending — skipTo's own guard also checks
    // advanceMut.isPending, so this holds even if the disabled attribute were ever dropped.
    fireEvent.click(skipItem)
    expect(advanceToLiveMatch).not.toHaveBeenCalled()
    expect(advanceLiveMatch).toHaveBeenCalledTimes(1)
  })

  it('guards "Iniciar 2º tempo" against an in-flight skip (regression: resume() was unguarded at the source)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'HALF_TIME', minute: 45 } as never)
    // Never resolves — simulates the "Avançar até o fim" skip staying in flight for the whole test.
    vi.mocked(advanceToLiveMatch).mockImplementation(() => new Promise(() => {}))
    renderPage()

    // No dialog auto-opens at half-time anymore, so "Iniciar 2º tempo" and the "Avançar até o
    // fim" menu item are both directly reachable — the exact scenario described in the bug: both
    // exposed at HALF_TIME.

    // Start a skip via the options menu while at half-time — advanceToMut is now in flight.
    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: /avançar até o fim/i }))
    await waitFor(() => expect(advanceToLiveMatch).toHaveBeenCalledWith('sg-1', 'FULL_TIME', 'test-token'))

    // "Iniciar 2º tempo" (onClick={resume}) must now be disabled...
    const resumeButton = await screen.findByRole('button', { name: /iniciar 2º tempo/i })
    await waitFor(() => expect(resumeButton).toBeDisabled())

    // ...and even a direct click (bypassing the disabled attribute, as fireEvent does) must not
    // sneak an advanceLiveMatch call through — this is what proves resume() itself is guarded,
    // not just the button's `disabled` prop, and is the part that fails pre-fix.
    fireEvent.click(resumeButton)
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  })

  it('does not race the per-minute advance when handleApply auto-resumes while a skip is in flight (regression: resume() must honor its in-flight guard from the handleApply auto-resume call site, not just from disabled buttons)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS', minute: 60,
      onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: false, onPitch: true, used: false, eligible: true }],
      bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
      subsRemaining: 5,
    } as never)
    // Never resolves — the skip (advanceToMut) stays in flight for the whole test, exactly
    // like the "Avançar até o fim" regression tests above.
    vi.mocked(advanceToLiveMatch).mockImplementation(() => new Promise(() => {}))
    // The sub resolves to PAUSED_INJURY, one of the statuses that makes handleApply call
    // resume() automatically (see the comment above that call in LiveMatchPage.tsx).
    vi.mocked(substituteLiveMatch).mockResolvedValue({
      ...STATE, status: 'PAUSED_INJURY', minute: 60, subsRemaining: 4,
    } as never)

    renderPage()

    // Start a skip via the options menu — advanceToMut is now in flight (and stays that way: the
    // mock never resolves).
    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    fireEvent.click(screen.getByRole('menuitem', { name: /avançar até o fim/i }))
    await waitFor(() => expect(advanceToLiveMatch).toHaveBeenCalledWith('sg-1', 'FULL_TIME', 'test-token'))

    // The substitution controls aren't gated on the skip's in-flight state, so the actions
    // dialog can still be opened and a sub applied while the skip is pending — this is the
    // real-world path that reaches handleApply's programmatic (non-button) resume() call, e.g.
    // the manager kicks off "Avançar até o fim" then reopens the dialog to make a sub before it
    // resolves.
    fireEvent.click(await screen.findByTestId('club-card-own'))
    const outSection = (await screen.findByText(/selecionar quem sai do campo/i)).closest('div') as HTMLElement
    fireEvent.click(within(outSection).getByText(/Titular/))
    await screen.findByText(/Substituições Pendentes/i)
    const inSection = screen.getByText(/selecionar quem entra no jogo/i).closest('div') as HTMLElement
    fireEvent.click(within(inSection).getByText('Reserva'))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /continuar/i }))

    await waitFor(() => expect(substituteLiveMatch).toHaveBeenCalledTimes(1))
    // The returned PAUSED_INJURY state has rendered — proves handleApply's setState() and the
    // synchronous auto-resume resume() call right underneath it have both already run.
    await screen.findByText(/Lesão! Faça uma substituição ou continue\./i)

    // resume()'s own guard (skipping || advanceToMut.isPending || advanceMut.isPending) must
    // have short-circuited the auto-resume, because the skip from "Avançar até o fim" is still
    // pending — advanceLiveMatch must never fire here.
    expect(advanceLiveMatch).not.toHaveBeenCalled()
    expect(advanceToLiveMatch).toHaveBeenCalledTimes(1)
  })

  it('hides "Avançar até o intervalo" once minute reaches 45, but keeps "Avançar até o fim" until full time', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', minute: 46 } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /opções do jogo/i }))
    expect(screen.getByRole('menuitem', { name: /avançar até o fim/i })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /avançar até o intervalo/i })).not.toBeInTheDocument()
  })

  it('hides both skip buttons at full time', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'FULL_TIME', minute: 90 } as never)
    renderPage()

    await screen.findByRole('button', { name: /^continuar$/i })
    // The whole options-menu trigger is hidden at full time — there is nothing left to skip to.
    expect(screen.queryByRole('button', { name: /opções do jogo/i })).not.toBeInTheDocument()
  })

  it('opens the Tática dialog, changes mentality, clicks Continuar, and calls changeTactics', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    vi.mocked(changeTactics).mockResolvedValue({ ...STATE, currentTactics: { mentality: 'ATTACKING', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' } } as never)

    renderPage()

    // The own club card opens the dialog on the substitutions tab — switch to Instruções táticas.
    fireEvent.click(await screen.findByTestId('club-card-own'))

    // The dialog should be open
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('tab', { name: /instruções táticas/i }))

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

    // Open the substitution dialog (the own club card's default entry point)
    fireEvent.click(await screen.findByTestId('club-card-own'))

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

    fireEvent.click(await screen.findByTestId('club-card-own'))
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
    fireEvent.click(await screen.findByTestId('club-card-own'))
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

  it('at half-time, shows the Intervalo banner and the Iniciar 2º tempo button on the match card', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 45, status: 'HALF_TIME' } as never)
    renderPage()
    expect(await screen.findByText(/intervalo/i)).toBeInTheDocument()
    // No dialog auto-opens anymore — "Iniciar 2º tempo" on the match card is the entry point
    // into the 2nd half instead.
    expect(await screen.findByRole('button', { name: /iniciar 2º tempo/i })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('at HALF_TIME no dialog is auto-opened', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 45, status: 'HALF_TIME' } as never)
    renderPage()
    await screen.findByRole('button', { name: /iniciar 2º tempo/i })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
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
      ...STATE, currentTactics: { mentality: 'ATTACKING', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
    } as never)

    renderPage()

    // Open the substitution dialog on tab 0 (the own club card's default)
    fireEvent.click(await screen.findByTestId('club-card-own'))
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

  it('clicking a ratings-row name for an own player opens read-only details with no watch star', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      playerRatings: [
        { playerId: 'p1', name: 'Camisa 9', position: 'ATT', minutesPlayed: 20, goals: 0, rating: 6.7 },
      ],
    } as never)
    vi.mocked(getPlayer).mockResolvedValue(MOCK_PLAYER as never)

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    fireEvent.click(await screen.findByText('Camisa 9'))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(getPlayer).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
    // Full player details render (attributes tab is the default), and no watch star for an
    // own-squad player.
    expect(within(dialog).getByText('Pos')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Observar jogador' })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Remover da observação' })).not.toBeInTheDocument()
  })

  it('clicking a ratings-row name for an opponent player opens details with a watch star', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      opponentRatings: [
        { playerId: 'o1', name: 'Rival Camisa 10', position: 'MID', minutesPlayed: 12, goals: 0, rating: 6.2 },
      ],
    } as never)
    vi.mocked(getPlayer).mockResolvedValue({ ...MOCK_PLAYER, id: 'o1', name: 'Rival Camisa 10' } as never)

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: /atuações/i }))
    fireEvent.click(await screen.findByText('Rival Camisa 10'))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(getPlayer).toHaveBeenCalledWith('sg-1', 'o1', 'test-token'))
    expect(await within(dialog).findByRole('button', { name: 'Observar jogador' })).toBeInTheDocument()
  })

  it('clicking a pre-match lineup name for the opponent side opens details with a watch star', async () => {
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
    vi.mocked(getPlayer).mockResolvedValue({ ...MOCK_PLAYER, id: 'a1', name: 'Goleiro Fora', position: 'GK' } as never)

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))
    fireEvent.click(listOccurrence(await screen.findAllByText('Goleiro Fora')))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(getPlayer).toHaveBeenCalledWith('sg-1', 'a1', 'test-token'))
    expect(await within(dialog).findByRole('button', { name: 'Observar jogador' })).toBeInTheDocument()
  })

  it('clicking a pre-match lineup name for the managed side opens details with no watch star', async () => {
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
    vi.mocked(getPlayer).mockResolvedValue({ ...MOCK_PLAYER, id: 'h1', name: 'Goleiro Casa', position: 'GK' } as never)

    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: /escalações/i }))
    fireEvent.click(listOccurrence(await screen.findAllByText('Goleiro Casa')))

    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(getPlayer).toHaveBeenCalledWith('sg-1', 'h1', 'test-token'))
    expect(within(dialog).queryByRole('button', { name: 'Observar jogador' })).not.toBeInTheDocument()
  })

  it('Rodada tab (Resultados sub-tab) renders one formatted row per parallel fixture from state', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'IN_PROGRESS',
      parallelScores: [
        { fixtureId: 'px1', homeShort: 'AVA', awayShort: 'FIG', homeGoals: 2, awayGoals: 1 },
        { fixtureId: 'px2', homeShort: 'JEC', awayShort: 'CHA', homeGoals: 0, awayGoals: 0 },
      ],
    } as never)
    renderPage()

    // Resultados is the default sub-tab, so no extra click is needed once on Rodada.
    fireEvent.click(await screen.findByRole('tab', { name: /rodada/i }))
    expect(await screen.findByText('AVA 2 x 1 FIG')).toBeInTheDocument()
    expect(screen.getByText('JEC 0 x 0 CHA')).toBeInTheDocument()
    expect(screen.getByTestId('parallel-px1')).toBeInTheDocument()
    expect(screen.getByTestId('parallel-px2')).toBeInTheDocument()
  })

  it('Rodada tab (Resultados sub-tab) shows an empty state when there are no other games this round', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', parallelScores: [] } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /rodada/i }))
    expect(await screen.findByText('Sem outros jogos nesta rodada')).toBeInTheDocument()
  })

  it('Rodada tab (Classificação sub-tab) fetches standings for the live competitionId and marks the managed club row', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', competitionId: 'comp-9' } as never)
    vi.mocked(getSaveGame).mockResolvedValue({ managedClubId: 'club-1' } as never)
    vi.mocked(getStandings).mockResolvedValue([
      { clubId: 'club-1', clubName: 'Criciúma EC', commonName: 'Tigrão', played: 3, won: 2, drawn: 1, lost: 0, goalsFor: 5, goalsAgainst: 2, goalDifference: 3, points: 7 },
      { clubId: 'club-2', clubName: 'Avaí FC', commonName: 'Leão', played: 3, won: 1, drawn: 0, lost: 2, goalsFor: 2, goalsAgainst: 4, goalDifference: -2, points: 3 },
    ] as never)
    renderPage()

    // The standings query is enabled on mount (state.competitionId is set), so it fires with
    // the live state's competitionId regardless of the active tab.
    await waitFor(() => expect(getStandings).toHaveBeenCalledWith('sg-1', 'comp-9', 'test-token'))

    fireEvent.click(await screen.findByRole('tab', { name: /rodada/i }))
    fireEvent.click(await screen.findByRole('tab', { name: /classificação/i }))
    expect(await screen.findByText('Tigrão')).toBeInTheDocument()
    expect(screen.getByText('Leão')).toBeInTheDocument()

    // Only the managed club's row carries the data-managed marker, and it is the Tigrão row.
    const managedRows = document.querySelectorAll('[data-managed="true"]')
    expect(managedRows).toHaveLength(1)
    expect(within(managedRows[0] as HTMLElement).getByText('Tigrão')).toBeInTheDocument()
  })

  it('shows the match stats in the Estatísticas do jogo tab', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      stats: {
        home: { ...STATE.stats.home, possession: 62, shots: 7 },
        away: { ...STATE.stats.away, possession: 38, shots: 2 },
      },
    } as never)
    renderPage()

    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas do jogo/i }))
    // STATE.stats (50/50 possession, every counter 0) is byte-identical to MatchStatsTab's own
    // EMPTY_STATS fallback (MatchStatsTab.tsx:20-36), so a test built on the shared fixture
    // can't tell `stats={state.stats}` apart from `stats={undefined}`. Override with asymmetric
    // values the fallback could never produce, and check each pair in DOM order (home value
    // first, away value second) so a home/away swap regression is also caught.
    const possessionValues = within(await screen.findByTestId('stat-possession')).getAllByText(/^(62%|38%)$/)
    expect(possessionValues.map(el => el.textContent)).toEqual(['62%', '38%'])

    const shotsValues = within(screen.getByTestId('stat-shots')).getAllByText(/^(7|2)$/)
    expect(shotsValues.map(el => el.textContent)).toEqual(['7', '2'])
  })

  it('the key-events block groups two goals by the same scorer into one line with both minutes', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      status: 'IN_PROGRESS',
      events: [
        {
          minute: 21, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Samba',
          playerInId: null, playerInName: null, severity: null, text: 'GOL! Samba abre o placar!',
        },
        {
          minute: 42, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Samba',
          playerInId: null, playerInName: null, severity: null, text: 'GOL! Samba de novo!',
        },
      ],
    } as never)
    renderPage()

    // Exercises the real path (LiveMatchPage -> MatchNarrationCard -> KeyEventsSummary ->
    // summarizeKeyEvents), not the grouping algorithm itself (matchKeyEvents.test.ts already
    // covers that) — proves the rendering keeps name and minutes together in one line.
    expect(await screen.findByText(/Samba 21', 42'/)).toBeInTheDocument()
  })

  it.each<[string, Partial<typeof STATE>]>([
    ['minute 0', { minute: 0, status: 'IN_PROGRESS' }],
    ['half-time', { minute: 45, status: 'HALF_TIME' }],
    ['a forced injury pause', { minute: 30, status: 'PAUSED_INJURY' }],
    ['a forced red-card pause', { minute: 55, status: 'PAUSED_RED' }],
  ])('hides the pause/play button at %s, since the action slot already shows a button there', async (_label, override) => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, ...override } as never)
    renderPage()
    // Wait for the page to settle on some recognisable content before asserting absence, so a
    // false negative can't come from asserting before the query resolves.
    await screen.findByRole('tab', { name: /partida/i })
    // `playing` now starts false and none of these states' autoResumedRef condition is met
    // (minute 0, or a status other than IN_PROGRESS/EXTRA_TIME), so the toggle — if it leaked
    // past the `!actionSlotShowsButton` gate — would read "Continuar", not "Pausar". Match the
    // toggle's exact accessible name either way ("Pausar" or "Continuar"): none of these states'
    // own action-slot buttons collide with an EXACT match on either word ("Iniciar partida",
    // "Iniciar 2º tempo" and "Continuar assim mesmo" all fail ^...$), so this single query still
    // catches the toggle rendering regardless of `playing`'s value.
    expect(screen.queryByRole('button', { name: /^(pausar|continuar)$/i })).not.toBeInTheDocument()
  })

  it('hides the pause/play button at full time, since the action slot already shows a button there', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    renderPage()
    // At full time the finish CTA legitimately reads exactly "Continuar" too, and `playing` is
    // false here (autoResumedRef's status check excludes FULL_TIME), so a toggle that leaked
    // past the gate would ALSO read "Continuar" — indistinguishable from the finish CTA by text
    // alone. Assert there is exactly one such button, not two, so a regression that renders both
    // is still caught.
    expect(await screen.findAllByRole('button', { name: /^continuar$/i })).toHaveLength(1)
  })

  it('shows the pause/play button during open play', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', minute: 12 } as never)
    renderPage()
    expect(await screen.findByRole('button', { name: /^pausar$/i })).toBeInTheDocument()
  })

  it('at minute 0, shows "Iniciar partida" and clicking it calls advanceLiveMatch once', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 0, status: 'IN_PROGRESS' } as never)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /iniciar partida/i }))
    await waitFor(() => expect(advanceLiveMatch).toHaveBeenCalledTimes(1))
  })

  it('at minute 0, does NOT auto-advance before "Iniciar partida" is pressed (regression: playing must default to false)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 0, status: 'IN_PROGRESS' } as never)
    renderPage()
    await screen.findByRole('button', { name: /iniciar partida/i })
    // The client-owned clock ticks every ADVANCE_MS (1.5s) — wait past that to prove kickoff
    // stays frozen with no button press. This is the test that actually pins the behaviour
    // change: without it, a future revert to `useState(true)` would pass every other test here
    // silently.
    await new Promise(resolve => setTimeout(resolve, 2000))
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  }, 10_000)

  it('auto-resumes play for a match reloaded past minute 0, with no "Iniciar partida" button and no click needed (the reload case)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 12, status: 'IN_PROGRESS' } as never)
    renderPage()
    expect(screen.queryByRole('button', { name: /iniciar partida/i })).not.toBeInTheDocument()
    await waitFor(() => expect(advanceLiveMatch).toHaveBeenCalledTimes(1), { timeout: 3000 })
  }, 10_000)

  it('does NOT auto-advance a match reloaded at half-time', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 45, status: 'HALF_TIME' } as never)
    renderPage()
    await screen.findByRole('button', { name: /iniciar 2º tempo/i })
    await new Promise(resolve => setTimeout(resolve, 2000))
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  }, 10_000)

  it('clicking the opponent card opens a scouting panel with the opponent formation as plain text, no mentality combobox, and no Continuar button (read-only)', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      opponentOnPitch: [
        { playerId: 'o1', name: 'Zagueiro Rival', position: 'DEF', overall: 65, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'STOPPER' },
      ],
      opponentTactics: { mentality: 'ATTACKING', gameStyle: 'PRESSAO_ALTA', attackZone: 'CENTRO', pressing: 'HIGH', marking: 'MAN_TIGHT', formation: '3-5-2' },
    } as never)
    renderPage()

    fireEvent.click(await screen.findByTestId('club-card-opponent'))

    const dialog = await screen.findByRole('dialog')
    // Scoped to the tactics rows block (not the whole dialog): LineupColumn also renders the
    // formation as a caption, so an unscoped query would hit two elements.
    const tacticsBlock = within(dialog).getByTestId('opponent-tactics')
    expect(within(tacticsBlock).getByText('3-5-2')).toBeInTheDocument()
    // 'Ofensiva' is MENTALITY_LABEL.ATTACKING, not the raw 'ATTACKING' enum value — proves the
    // panel labels tactics through the shared map, same as the user's own tactics dialog.
    expect(within(tacticsBlock).getByText('Ofensiva')).toBeInTheDocument()

    // Genuinely read-only: the own tactics dialog renders a `combobox` for mentality; if this
    // panel regressed to reuse a (disabled) Select instead of plain text, it would still expose
    // that combobox role, since `disabled` doesn't remove an element's role. No apply action
    // either — only "Fechar".
    expect(within(dialog).queryByRole('combobox')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: /continuar/i })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /fechar/i })).toBeInTheDocument()
  })

  it('shows the opponent\'s players in the scouting panel, not the managed club\'s, and does not invert when managedIsHome is false', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE,
      managedIsHome: false,
      onPitch: [
        { playerId: 'p1', name: 'Managed Titular', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX' },
      ],
      opponentOnPitch: [
        { playerId: 'o1', name: 'Home Rival Titular', position: 'MID', overall: 65, fitness: 100, hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX' },
      ],
    } as never)
    renderPage()

    // Managed club plays away, so its own club card carries the away short name — the opponent
    // card (this panel's entry point) is therefore the home one.
    expect(await screen.findByTestId('club-card-own')).toHaveTextContent(STATE.awayClubShortName)
    fireEvent.click(screen.getByTestId('club-card-opponent'))

    const dialog = await screen.findByRole('dialog')
    // The opponent, playing at home in this fixture, is the AI-controlled club — the panel must
    // show ITS roster (opponentOnPitch), never the managed club's (onPitch), even though the
    // managed club is the one playing away. Each name can render twice inside the dialog (list
    // + pitch token), so assert presence/absence rather than a single-match getByText.
    expect(within(dialog).getAllByText('Home Rival Titular').length).toBeGreaterThan(0)
    expect(within(dialog).queryAllByText('Managed Titular')).toHaveLength(0)
  })

  it('blocks the per-minute clock while the scouting panel is open', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, status: 'IN_PROGRESS', minute: 12 } as never)
    renderPage()

    fireEvent.click(await screen.findByTestId('club-card-opponent'))
    await screen.findByRole('dialog')

    // The client-owned clock ticks every ADVANCE_MS (1.5s) — wait past that to prove the
    // scouting panel's open state suppresses the tick, mirroring the shape of the "blocks the
    // per-minute clock" assertions on the skip-ahead tests above.
    await new Promise(resolve => setTimeout(resolve, 2000))
    expect(advanceLiveMatch).not.toHaveBeenCalled()
  }, 10_000)
})
