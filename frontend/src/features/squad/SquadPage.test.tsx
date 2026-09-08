import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SquadPage } from './SquadPage'
import { AuthProvider } from '../../auth/AuthContext'
import { ApiError } from '../../api/client'
import { useIsMobile } from '../../lib/useIsMobile'
import type { PlayerResponse, SquadResponse, ClubSummary, LoanOfferResult } from '../../api/types'

vi.mock('../../api/career', () => ({
  getSquad: vi.fn(),
  getLineup: vi.fn(),
  setLineup: vi.fn(),
  setTraining: vi.fn(),
  getTactics: vi.fn(),
  setTactics: vi.fn(),
  renewContract: vi.fn(),
  listPlayer: vi.fn(),
  unlistPlayer: vi.fn(),
  getFinances: vi.fn(),
  terminateContract: vi.fn(),
  getClubs: vi.fn(),
  loanOut: vi.fn(),
  acceptLoanCounter: vi.fn(),
  getSaveGame: vi.fn(),
  getLoanOffers: vi.fn(),
  withdrawLoanOffer: vi.fn(),
}))

vi.mock('../../lib/useIsMobile', () => ({ useIsMobile: vi.fn(() => false) }))

import {
  getSquad, getLineup, setLineup, getTactics, renewContract, listPlayer, unlistPlayer, getFinances,
  terminateContract, getClubs, loanOut, acceptLoanCounter, getSaveGame, getLoanOffers, withdrawLoanOffer,
} from '../../api/career'

const mockGetSquad = vi.mocked(getSquad)
const mockGetLineup = vi.mocked(getLineup)
const mockSetLineup = vi.mocked(setLineup)
const mockRenewContract = vi.mocked(renewContract)
const mockListPlayer = vi.mocked(listPlayer)
const mockUnlistPlayer = vi.mocked(unlistPlayer)
const mockGetFinances = vi.mocked(getFinances)
const mockTerminateContract = vi.mocked(terminateContract)
const mockGetClubs = vi.mocked(getClubs)
const mockLoanOut = vi.mocked(loanOut)
const mockAcceptLoanCounter = vi.mocked(acceptLoanCounter)
const mockGetSaveGame = vi.mocked(getSaveGame)
const mockGetLoanOffers = vi.mocked(getLoanOffers)
const mockWithdrawLoanOffer = vi.mocked(withdrawLoanOffer)

// `as const` narrows injurySeverity to the literal 'NONE' so it satisfies
// PlayerResponse's union type (not widened to string) when spread into fixtures.
const STATE = { detailedPosition: null, detailedPositionLabel: null, form: 0, morale: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0, injurySeverity: 'NONE' as const, eligible: true, role: null, contractYears: 2, value: 1500000, wageDemand: 90000, terminationFee: 5880000, fitness: 100, attributes: [
  { key: 'FINALIZACAO', label: 'Finalização', group: 'TECNICO' as const, value: 55 },
  { key: 'VISAO', label: 'Visão de jogo', group: 'MENTAL' as const, value: 50 },
  { key: 'VELOCIDADE', label: 'Velocidade', group: 'FISICO' as const, value: 60 },
  { key: 'REFLEXOS', label: 'Reflexos', group: 'GOLEIRO' as const, value: 70 },
] }

const GK: PlayerResponse = {
  id: 'p-gk',
  name: 'Goleiro Teste',
  position: 'GK',
  age: 25,
  overall: 72,
  pace: 50,
  finishing: 20,
  passing: 40,
  tackling: 30,
  goalkeeping: 80,
  stamina: 60,
  ...STATE,
}

const makeOutfield = (id: string, name: string, pos: 'DEF' | 'MID' | 'ATT'): PlayerResponse => ({
  id,
  name,
  position: pos,
  age: 24,
  overall: 70,
  pace: 70,
  finishing: 65,
  passing: 68,
  tackling: 60,
  goalkeeping: 10,
  stamina: 72,
  ...STATE,
})

const DEF1 = makeOutfield('p-d1', 'Defensor Um', 'DEF')
// DEF1 carries a detailed position so the squad table can show its short abbr ("LE").
DEF1.detailedPosition = 'LATERAL_ESQUERDO'
DEF1.detailedPositionLabel = 'Lateral esquerdo'
const DEF2 = makeOutfield('p-d2', 'Defensor Dois', 'DEF')
const DEF3 = makeOutfield('p-d3', 'Defensor Tres', 'DEF')
const DEF4 = makeOutfield('p-d4', 'Defensor Quatro', 'DEF')
const MID1 = makeOutfield('p-m1', 'Meio Um', 'MID')
const MID2 = makeOutfield('p-m2', 'Meio Dois', 'MID')
const MID3 = makeOutfield('p-m3', 'Meio Tres', 'MID')
const MID4 = makeOutfield('p-m4', 'Meio Quatro', 'MID')
const ATT1 = makeOutfield('p-a1', 'Atacante Um', 'ATT')
const ATT2 = makeOutfield('p-a2', 'Atacante Dois', 'ATT')
const ATT3 = makeOutfield('p-a3', 'Atacante Tres', 'ATT')
const ATT4 = makeOutfield('p-a4', 'Atacante Quatro', 'ATT')
const GK2: PlayerResponse = {
  id: 'p-gk2',
  name: 'Goleiro Dois',
  position: 'GK',
  age: 28,
  overall: 75,
  pace: 52,
  finishing: 18,
  passing: 42,
  tackling: 28,
  goalkeeping: 82,
  stamina: 65,
  ...STATE,
}

const SUSPENDED = makeOutfield('p-susp', 'Banido Um', 'MID')
SUSPENDED.eligible = false
SUSPENDED.suspensionRemaining = 1

const INJURED = makeOutfield('p-inj', 'Lesionado Um', 'DEF')
INJURED.eligible = false
INJURED.injuryMatches = 3
INJURED.injurySeverity = 'MINOR'

const LOW_FITNESS = makeOutfield('p-lf', 'Baixo Fôlego', 'MID')
LOW_FITNESS.fitness = 45

const LISTED = makeOutfield('p-listed', 'Listado Um', 'MID')
LISTED.transferListed = true
LISTED.listedReason = 'UNUSED'

const ALL_OUTFIELD = [DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]

const squad: SquadResponse = {
  players: [GK, GK2, ...ALL_OUTFIELD, MID4, ATT4, SUSPENDED, INJURED, LOW_FITNESS, LISTED],
  primaryColor: '#f7d417',
  secondaryColor: '#111111',
}

// The managed club itself ('club-1', matching renderWithProviders' default clubId) must never be
// offered as a loan destination — only other clubs make sense as a borrower.
const SAVE_OPEN = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'c-1', competitionName: 'Catarinense',
  managedClubId: 'club-1', managedClubName: 'Criciúma', currentRound: 0, totalRounds: 18,
  transferWindowOpen: true,
}

const CLUBS: ClubSummary[] = [
  { id: 'club-1', name: 'Criciúma EC', commonName: 'Criciúma', shortName: 'CRI', primaryColor: '#f7d417', secondaryColor: '#111111', reputation: 74, squadSize: 22 },
  { id: 'club-avi', name: 'Avaí FC', commonName: 'Avaí', shortName: 'AVA', primaryColor: '#0000aa', secondaryColor: '#ffffff', reputation: 80, squadSize: 25 },
]

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

// `qc` is optional so a test that needs to spy on the client (e.g. invalidateQueries) can build
// its own and pass it in — otherwise a fresh one is created per render as before.
function renderWithProviders(saveGameId = 'sg-1', clubId = 'club-1', qc: QueryClient = makeQueryClient()) {
  return render(
    <MemoryRouter initialEntries={[`/squad?saveGameId=${saveGameId}&clubId=${clubId}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <SquadPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

// The squad-selection button for a player (aria-label "Escalar <name>"). A starter's button
// shows their position abbreviation (GK/DC/MC/AT), a substitute shows "Sub N", unpicked "+".
const pickBtn = (name: string) => screen.getByRole('button', { name: new RegExp(`escalar ${name}`, 'i') })

// Opens the Função/Foco menu for a starter by right-clicking their token on the pitch — the
// desktop route now that the Plantel table no longer carries those columns (RoleContextMenu).
// Switches to the Táticas de jogo tab first, matching the real user flow.
function openRoleMenu(name: string) {
  fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
  const pitch = screen.getByTestId('pitch')
  const token = within(pitch).getByText(name).closest('[data-slot-index]')
  fireEvent.contextMenu(token as HTMLElement, { clientX: 100, clientY: 200 })
}

describe('SquadPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSquad.mockResolvedValue(squad)
    mockGetLineup.mockResolvedValue(null)
    mockSetLineup.mockResolvedValue({
      lineupId: 'l-1',
      clubId: 'club-1',
      formation: '4-3-3',
      starters: [],
      bench: [],
    })
    vi.mocked(getTactics).mockResolvedValue({
      mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL',
      penaltyTakerId: null, freekickTakerId: null, cornerTakerId: null,
    })
    mockGetFinances.mockResolvedValue({ balance: 20000000, transactions: [] })
    mockGetClubs.mockResolvedValue(CLUBS)
    mockGetSaveGame.mockResolvedValue(SAVE_OPEN)
    mockGetLoanOffers.mockResolvedValue([])
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  afterEach(() => {
    vi.mocked(useIsMobile).mockReturnValue(false)
  })

  it('mobile: shows only the player name until expanded, then reveals position/OVR/fitness', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true)
    renderWithProviders()

    await screen.findByText('Goleiro Teste')
    expect(screen.queryByText('OVR 72')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /ver detalhes de goleiro teste/i }))

    expect(await screen.findByText('OVR 72')).toBeInTheDocument()
    expect(screen.getByText('GK')).toBeInTheDocument()
  })

  it('shows the detailed position short abbr in the squad table, coarse fallback otherwise', async () => {
    renderWithProviders()
    // DEF1 has detailedPosition LATERAL_ESQUERDO → "LE"; other defenders fall back to coarse "DEF".
    expect(await screen.findByText('Defensor Um')).toBeInTheDocument()
    expect(screen.getAllByText('LE').length).toBeGreaterThan(0)
    expect(screen.getAllByText('DEF').length).toBeGreaterThan(0)
  })

  it('labels a starter with the slot they occupy, not their own position', async () => {
    // DEF1 is a LATERAL_ESQUERDO improvised into slot 2 of 4-3-3 — a centre-back slot. The
    // Escalação chip must say where he PLAYS ("ZAG"); only the Pos column says what he is ("LE").
    // Before this, both columns showed "LE" and the improvisation was invisible in the table.
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null, slotIndex: 0 },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null, slotIndex: 1 },
        { playerId: DEF1.id, role: 'DEFENDER', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'DEFENDER', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null, slotIndex: 4 },
        { playerId: MID1.id, role: 'HOLDING', instruction: null, slotIndex: 5 },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 6 },
        { playerId: MID3.id, role: 'PLAYMAKER', instruction: null, slotIndex: 7 },
        { playerId: ATT1.id, role: 'WINGER', instruction: null, slotIndex: 8 },
        { playerId: ATT2.id, role: 'POACHER', instruction: null, slotIndex: 9 },
        { playerId: ATT3.id, role: 'WINGER', instruction: null, slotIndex: 10 },
      ],
      bench: [],
    })
    renderWithProviders()

    await screen.findAllByText('Defensor Um')
    expect(pickBtn('Defensor Um')).toHaveTextContent('ZAG')
    // A natural starter still reads as their own position: DEF4 sits in slot 4 (LE).
    expect(pickBtn('Defensor Quatro')).toHaveTextContent('LE')
  })

  it('auto-pick treats an alternate position as natural, not as improvisation', async () => {
    // Five defenders for the back four, and the ONLY player who can play left-back does it
    // through an alternate position while being the lowest-rated of them. If altPositions never
    // reaches assignSlots, the LE slot looks unfillable, sorts last, and its distance fallback
    // hands the shirt to the higher-rated pure centre-back — benching the one real left-back and
    // fielding an improviser the server would never pick.
    const def = (id: string, name: string, overall: number,
                 detailed: PlayerResponse['detailedPosition'], alts?: PlayerResponse['altPositions']) =>
      ({ ...makeOutfield(id, name, 'DEF'), overall, detailedPosition: detailed, altPositions: alts })
    const mid = (id: string, name: string, detailed: PlayerResponse['detailedPosition']) =>
      ({ ...makeOutfield(id, name, 'MID'), detailedPosition: detailed })
    const att = (id: string, name: string, detailed: PlayerResponse['detailedPosition']) =>
      ({ ...makeOutfield(id, name, 'ATT'), detailedPosition: detailed })

    mockGetSquad.mockResolvedValue({
      players: [
        { ...GK, detailedPosition: 'GOLEIRO' },
        def('p-ld', 'Lateral Direito', 75, 'LATERAL_DIREITO'),
        def('p-zbig', 'Zagueiro Forte', 90, 'ZAGUEIRO'),
        def('p-z1', 'Zagueiro Um', 80, 'ZAGUEIRO'),
        def('p-z2', 'Zagueiro Dois', 78, 'ZAGUEIRO'),
        def('p-zalt', 'Canhoto Versatil', 60, 'ZAGUEIRO', ['LATERAL_ESQUERDO']),
        mid('p-vol', 'Volante Um', 'VOLANTE'),
        mid('p-mc', 'Meia Central', 'MEIA_CENTRAL'),
        mid('p-mei', 'Meia Armador', 'MEIA_ARMADOR'),
        att('p-pd', 'Ponta Direita', 'PONTA_DIREITA'),
        att('p-st', 'Centroavante Um', 'ATACANTE'),
        att('p-pe', 'Ponta Esquerda', 'PONTA_ESQUERDA'),
      ],
      primaryColor: '#f7d417',
      secondaryColor: '#111111',
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Canhoto Versatil'))

    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    expect(pickBtn('Canhoto Versatil')).toHaveTextContent('LE')
    // …and the centre-back who would otherwise have been improvised there is the one left out.
    expect(pickBtn('Zagueiro Dois')).toHaveTextContent('+')
  })

  it('renders the formation pitch with the selected XI', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null }, { playerId: DEF2.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null }, { playerId: DEF4.id, role: 'FULLBACK', instruction: null },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null }, { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null }, { playerId: MID4.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: ATT1.id, role: 'POACHER', instruction: null }, { playerId: ATT2.id, role: 'POACHER', instruction: null },
      ],
      bench: [],
    })
    renderWithProviders()
    // The pitch lives in the "Táticas de jogo" tab panel, hidden until that tab is active.
    fireEvent.click(await screen.findByRole('tab', { name: 'Táticas de jogo' }))
    expect(await screen.findByTestId('pitch')).toBeInTheDocument()
    expect(screen.getAllByTestId('player-token')).toHaveLength(11)
  })

  it('starts on the Plantel tab with the squad table and no visible pitch', async () => {
    renderWithProviders()
    const plantelTab = await screen.findByRole('tab', { name: 'Plantel' })
    expect(plantelTab).toHaveAttribute('aria-selected', 'true')
    const taticasTab = screen.getByRole('tab', { name: 'Táticas de jogo' })
    expect(taticasTab).toBeInTheDocument()
    // The pitch container lives in the hidden tab-2 panel, identified via aria-controls
    // (querying by accessible name on a `hidden` element is unreliable in jsdom).
    const panelId = taticasTab.getAttribute('aria-controls')!
    const tacticsPanel = document.getElementById(panelId)!
    expect(tacticsPanel).not.toBeVisible()
  })

  it('renders training controls seeded from the squad payload when the Treino tab is selected', async () => {
    // Proves the wiring only (the controls' own behaviour — options, changes, saving — is
    // TrainingTab's own test suite now): SquadPage seeds `trainingAssignments` from the squad
    // query and passes it straight through as TrainingTab's `assignments` prop.
    mockGetSquad.mockResolvedValue({
      ...squad,
      players: squad.players.map(p =>
        p.id === GK.id ? { ...p, trainingFocus: 'REFLEXOS' as const, trainingIntensity: 'INTENSA' as const } : p),
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))

    expect(screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` })).toHaveValue('REFLEXOS')
    expect(screen.getByRole('combobox', { name: `Intensidade de treino de ${GK.name}` })).toHaveValue('INTENSA')
  })

  it('keeps an unsaved training focus change after switching away from and back to Treino', async () => {
    // `trainingAssignments` lives in SquadPage, not TrainingTab, SPECIFICALLY because the Treino
    // panel unmounts on tab-away (unlike Plantel/Táticas, which stay mounted-but-hidden) — state
    // owned by the tab itself would discard this unsaved edit. Proves the invariant the whole
    // extraction was designed around.
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))
    fireEvent.change(
      screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` }),
      { target: { value: 'REFLEXOS' } },
    )
    expect(screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` })).toHaveValue('REFLEXOS')

    fireEvent.click(screen.getByRole('tab', { name: 'Plantel' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))

    expect(screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` })).toHaveValue('REFLEXOS')
  })

  it('shows tactics controls side by side with the pitch in the Táticas de jogo tab', async () => {
    renderWithProviders()
    fireEvent.click(await screen.findByRole('tab', { name: 'Táticas de jogo' }))
    const panel = screen.getByRole('tabpanel', { name: 'Táticas de jogo' })
    expect(panel).toBeVisible()
    // The instructions panel is permanently visible now (no collapse/expand toggle).
    expect(within(panel).queryByRole('button', { name: /Instruções da equipe/ })).not.toBeInTheDocument()
    expect(within(panel).getByText('Instruções da equipe')).toBeInTheDocument()
    expect(await within(panel).findByLabelText('Mentalidade')).toBeInTheDocument()
    expect(within(panel).getByTestId('pitch')).toBeInTheDocument()
  })

  it('preserves an unsaved lineup selection across tab switches', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(pickBtn('goleiro teste'))
    fireEvent.click(screen.getByRole('tab', { name: 'Táticas de jogo' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Plantel' }))
    // Still exactly one starter selected (helper line reflects it).
    expect(screen.getByText(/Titulares 1\/11/)).toBeInTheDocument()
  })

  it('renders player names after loading', async () => {
    renderWithProviders()
    await waitFor(() => {
      expect(screen.getByText('Goleiro Teste')).toBeInTheDocument()
    })
    expect(screen.getByText('Defensor Um')).toBeInTheDocument()
    expect(screen.getByText('Atacante Um')).toBeInTheDocument()
  })

  it('enables "Salvar escalação" when exactly 11 players are selected including exactly 1 GK', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    // Select 11 players: 1 GK + 10 outfield (excluding GK2)
    const validSelection = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of validSelection) {
      fireEvent.click(pickBtn(player.name))
    }

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    expect(saveBtn).not.toBeDisabled()
  })

  it('writes the saved lineup directly into the lineup cache so the advance gate refreshes without a refetch', async () => {
    // Regression: the layout's "Escale seu time" warning + disabled Avançar are driven by the
    // ['lineup', saveGameId, clubId] query. Saving a lineup must update it, otherwise the
    // warning persists and Avançar stays disabled until a full page reload — now done via a
    // direct cache write (write-through) instead of invalidate-then-refetch.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockSetLineup.mockResolvedValue({
      lineupId: 'l-2', clubId: 'club-1', formation: '4-3-3',
      starters: [{ playerId: GK.id, role: 'GOALKEEPER', instruction: null }], bench: [],
    })
    render(
      <MemoryRouter initialEntries={['/squad?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SquadPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('Goleiro Teste'))

    const validSelection = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of validSelection) {
      fireEvent.click(pickBtn(player.name))
    }
    fireEvent.click(screen.getByRole('button', { name: /salvar escala/i }))

    await waitFor(() =>
      expect(qc.getQueryData(['lineup', 'sg-1', 'club-1'])).toEqual(
        expect.objectContaining({ lineupId: 'l-2' }),
      ),
    )
    // getLineup itself was only called once (the initial mount) — no extra round-trip.
    expect(mockGetLineup).toHaveBeenCalledTimes(1)
  })

  it('does not refetch the squad on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/squad?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SquadPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
  })

  it('an extra attacker beyond the formation\'s ATT slots still starts, taking any other free slot rather than the bench', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Atacante Um'))

    // Default formation 4-3-3 allows exactly 3 attackers.
    for (const player of [ATT1, ATT2, ATT3]) {
      fireEvent.click(pickBtn(player.name))
    }
    // The chip names the SLOT, so a 4-3-3 attacker reads as one of that line's slots.
    expect(pickBtn('atacante um')).toHaveTextContent(/^(PD|ATA|PE)$/)

    // The attacking line is full, but firstFreeSlotFor's last-resort fallback offers ANY free
    // slot rather than the bench — fielding a 4th attacker out of position (here, in the still-
    // open GK slot) is the manager's call, not something the UI refuses on their behalf.
    fireEvent.click(pickBtn('atacante quatro'))
    expect(pickBtn('atacante quatro')).toHaveTextContent('GOL')
  })

  it('keeps button disabled when fewer than 11 are selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(pickBtn('goleiro teste'))

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    expect(saveBtn).toBeDisabled()
  })

  it('a second goalkeeper still starts, taking any other free slot rather than the bench (blocked only at save time)', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Dois'))

    fireEvent.click(pickBtn('goleiro teste'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('GOL')

    // The GK slot is taken, but firstFreeSlotFor's last-resort fallback offers ANY free slot —
    // Goleiro Dois starts outfield instead of dropping to the bench. Two goalkeepers in the XI
    // is still illegal, but that's enforced by `validate` at save time, not by slot assignment.
    fireEvent.click(pickBtn('goleiro dois'))
    expect(pickBtn('goleiro dois')).toHaveTextContent('LD')
  })

  it('does not let a suspended player be selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Banido Um'))
    const btn = pickBtn('banido um')
    expect(btn).toBeDisabled()
    expect(screen.getByRole('img', { name: /suspenso — 1 jogo/i })).toBeInTheDocument()
  })

  it('shows a "(Leve)" injury icon for an injured player and disables their button', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Lesionado Um'))
    const btn = pickBtn('lesionado um')
    expect(btn).toBeDisabled()
    expect(screen.getByRole('img', { name: /lesionado \(leve\) — 3 jogo/i })).toBeInTheDocument()
  })

  it('renews a contract from the details dialog', async () => {
    mockRenewContract.mockResolvedValue({ playerId: GK.id, name: 'Goleiro Teste', wage: 90000, contractYears: 3 })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    // Contract term is shown and Renovar lives alongside the salary in the dialog.
    expect(within(dialog).getByText(/2 ano\(s\)/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: /renovar/i }))
    await waitFor(() => expect(mockRenewContract).toHaveBeenCalled())
  })

  it('optimistically shows the new contract term in the still-open dialog before the request resolves', async () => {
    let resolveRenew!: (v: { playerId: string; name: string; wage: number; contractYears: number }) => void
    mockRenewContract.mockReturnValue(new Promise(resolve => { resolveRenew = resolve }))
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    expect(within(dialog).getByText(/2 ano\(s\)/)).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: /renovar/i }))

    // Patched immediately — before the mocked request has resolved — and the same dialog
    // instance (not a reopened one) reflects it, since it now tracks the query cache.
    await waitFor(() => expect(within(dialog).getByText(/3 ano\(s\)/)).toBeInTheDocument())
    expect(screen.getByRole('dialog')).toBe(dialog)

    resolveRenew({ playerId: GK.id, name: 'Goleiro Teste', wage: 90000, contractYears: 3 })
  })

  it('rolls back the contract term if renewing fails', async () => {
    let rejectRenew!: (e: Error) => void
    mockRenewContract.mockReturnValue(new Promise((_, reject) => { rejectRenew = reject }))
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    expect(within(dialog).getByText(/2 ano\(s\)/)).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: /renovar/i }))
    await waitFor(() => expect(within(dialog).getByText(/3 ano\(s\)/)).toBeInTheDocument())

    rejectRenew(new Error('network'))

    // Rolled back once the rejection settles.
    await waitFor(() => expect(within(dialog).getByText(/2 ano\(s\)/)).toBeInTheDocument())
  })

  it('opens the termination dialog from the contract tab and terminates on confirm', async () => {
    mockTerminateContract.mockResolvedValue({
      playerId: 'p-m1', playerName: 'Meio Um', fee: 5880000, newBalance: 14120000,
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^rescindir$/i }))

    // TerminateDialog renders its own MUI Dialog on top of the still-open details dialog, so a
    // bare `dialog` role query is now ambiguous — scope by the terminate dialog's own content
    // (its data-testid and its own buttons) instead.
    expect(screen.getByTestId('termination-fee')).toHaveTextContent('5.880.000')
    fireEvent.click(screen.getByRole('button', { name: /confirmar rescisão/i }))

    await waitFor(() =>
      expect(mockTerminateContract).toHaveBeenCalledWith('sg-1', 'p-m1', expect.any(String)))
  })

  it('reports what the termination cost once it succeeds', async () => {
    mockTerminateContract.mockResolvedValue({
      playerId: 'p-m1', playerName: 'Meio Um', fee: 5880000, newBalance: 14120000,
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^rescindir$/i }))
    fireEvent.click(screen.getByRole('button', { name: /confirmar rescisão/i }))

    // Both dialogs close on success, so this toast is the only report of the fee and the balance.
    const toast = await screen.findByText(/rescindido/i)
    expect(toast).toHaveTextContent('Meio Um')
    expect(toast).toHaveTextContent('5.880.000')
    expect(toast).toHaveTextContent('14.120.000')
  })

  it('blocks termination when the balance will not cover the fee', async () => {
    mockGetFinances.mockResolvedValue({ balance: 1000, transactions: [] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^rescindir$/i }))

    expect(screen.getByRole('button', { name: /confirmar rescisão/i })).toBeDisabled()
    expect(screen.getByText(/saldo insuficiente/i)).toBeInTheDocument()
    expect(mockTerminateContract).not.toHaveBeenCalled()
  })

  it('disables the Emprestar action with a visible reason when the contract is too short to loan out', async () => {
    // The backend rejects a loan with LOAN_CONTRACT_TOO_SHORT below 2 years left — the button
    // must say so before the click, not after a failed round-trip.
    const shortContract = makeOutfield('p-short', 'Contrato Curto', 'DEF')
    shortContract.contractYears = 1
    mockGetSquad.mockResolvedValue({ ...squad, players: [...squad.players, shortContract] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Contrato Curto'))
    fireEvent.click(screen.getByText('Contrato Curto'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))

    const loanBtn = within(dialog).getByRole('button', { name: /^emprestar$/i })
    expect(loanBtn).toBeDisabled()
    expect(within(dialog).getByText(/menos de 2 anos/i)).toBeInTheDocument()

    fireEvent.click(loanBtn)
    expect(mockLoanOut).not.toHaveBeenCalled()
    expect(screen.queryByRole('combobox', { name: /clube de destino/i })).not.toBeInTheDocument()
  })

  it('disables the Emprestar action with a visible reason when the player is himself on loan from elsewhere', async () => {
    // offerLoanOut filters out any player where isOnLoan() is true — a club can't re-lend a
    // player it doesn't own outright. Same "explain before the click" principle as the
    // contract-length guard above.
    const borrowed = makeOutfield('p-borrowed-short', 'Emprestado Curto', 'DEF')
    borrowed.onLoanFromClubName = 'Avaí FC'
    mockGetSquad.mockResolvedValue({ ...squad, players: [...squad.players, borrowed] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Emprestado Curto'))
    fireEvent.click(screen.getByText('Emprestado Curto'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))

    const loanBtn = within(dialog).getByRole('button', { name: /^emprestar$/i })
    expect(loanBtn).toBeDisabled()
    expect(within(dialog).getByText(/emprestado por outro clube/i)).toBeInTheDocument()

    fireEvent.click(loanBtn)
    expect(mockLoanOut).not.toHaveBeenCalled()
    expect(screen.queryByRole('combobox', { name: /clube de destino/i })).not.toBeInTheDocument()
  })

  it('disables the Emprestar action with a visible reason when the transfer window is closed', async () => {
    // LoanService.requireWindowOpen now gates offerLoanOut server-side, mirroring TransferService.
    // "Pedir emprestado" on TransferMarketPage was already gated this way; "Emprestar" here must
    // match, or a closed-window loan-out would only fail after the round-trip.
    mockGetSaveGame.mockResolvedValue({ ...SAVE_OPEN, transferWindowOpen: false })
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))

    const loanBtn = await within(dialog).findByRole('button', { name: /^emprestar$/i })
    expect(loanBtn).toBeDisabled()
    expect(within(dialog).getByText(/janela de transferências fechada/i)).toBeInTheDocument()

    fireEvent.click(loanBtn)
    expect(mockLoanOut).not.toHaveBeenCalled()
    expect(screen.queryByRole('combobox', { name: /clube de destino/i })).not.toBeInTheDocument()
  })

  it('proposes a loan for a player with a long enough contract, offering every club but the manager\'s own', async () => {
    mockLoanOut.mockResolvedValue({
      offerId: 'o1', playerId: 'p-m1', playerName: 'Meio Um', status: 'ACCEPTED',
      managedClubIsOwner: true,
      ownerWageShare: 60, mayFaceOwner: true, counterOwnerWageShare: null, counterMayFaceOwner: null, refusal: null,
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^emprestar$/i }))

    const clubPicker = await screen.findByRole('combobox', { name: /clube de destino/i })
    expect(within(clubPicker).getAllByRole('option').map(o => o.textContent))
      .toEqual(expect.arrayContaining(['Avaí FC']))
    expect(within(clubPicker).queryByText('Criciúma EC')).not.toBeInTheDocument()

    fireEvent.change(clubPicker, { target: { value: 'club-avi' } })
    fireEvent.change(screen.getByLabelText(/parcela salarial/i), { target: { value: '60' } })
    fireEvent.click(screen.getByRole('switch', { name: /pode enfrentar meu clube/i }))
    fireEvent.click(screen.getByRole('button', { name: /propor empréstimo/i }))

    await waitFor(() => expect(mockLoanOut).toHaveBeenCalledWith('sg-1',
      { playerId: 'p-m1', borrowerClubId: 'club-avi', ownerWageShare: 60, mayFaceOwner: true }, 'test-token'))
    expect(await screen.findByText(/empréstimo aprovado/i)).toBeInTheDocument()
  })

  it('does not invalidate squad/finances/market/lineup when the loan-out proposal is rejected — nothing moved', async () => {
    mockLoanOut.mockResolvedValue({
      offerId: 'o1', playerId: 'p-m1', playerName: 'Meio Um', status: 'REJECTED',
      managedClubIsOwner: true,
      ownerWageShare: 60, mayFaceOwner: true, counterOwnerWageShare: null, counterMayFaceOwner: null,
      refusal: 'KEY_PLAYER',
    })
    const qc = makeQueryClient()
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    renderWithProviders('sg-1', 'club-1', qc)
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^emprestar$/i }))
    fireEvent.change(await screen.findByRole('combobox', { name: /clube de destino/i }), { target: { value: 'club-avi' } })
    fireEvent.click(screen.getByRole('button', { name: /propor empréstimo/i }))

    expect(await screen.findByText('O clube não abre mão dele: é peça-chave do elenco.')).toBeInTheDocument()
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', 'club-1'] })
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['finances', 'sg-1', 'club-1'] })
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['market', 'sg-1'] })
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['lineup', 'sg-1', 'club-1'] })
  })

  it('shows the AI\'s counter terms, accepts them through acceptLoanCounter, and refreshes the pending-loan listing both times', async () => {
    mockLoanOut.mockResolvedValue({
      offerId: 'o2', playerId: 'p-m1', playerName: 'Meio Um', status: 'COUNTERED',
      managedClubIsOwner: true,
      ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 75, counterMayFaceOwner: true, refusal: 'TERMS_REJECTED',
    })
    mockAcceptLoanCounter.mockResolvedValue({
      offerId: 'o2', playerId: 'p-m1', playerName: 'Meio Um', status: 'ACCEPTED',
      managedClubIsOwner: true,
      ownerWageShare: 75, mayFaceOwner: true, counterOwnerWageShare: null, counterMayFaceOwner: null, refusal: null,
    })
    const qc = makeQueryClient()
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    renderWithProviders('sg-1', 'club-1', qc)
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(screen.getByText('Meio Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^emprestar$/i }))
    fireEvent.change(await screen.findByRole('combobox', { name: /clube de destino/i }), { target: { value: 'club-avi' } })
    fireEvent.click(screen.getByRole('button', { name: /propor empréstimo/i }))

    // The COUNTERED result just produced is a new open offer — PendingLoanOffers must pick it up
    // immediately (not after its own 30s staleTime lapses) even though this dialog is what
    // produced it, not the panel.
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['loanOffers', 'sg-1'] }))
    invalidateSpy.mockClear()

    fireEvent.click(await screen.findByRole('button', { name: /aceitar contraproposta/i }))
    await waitFor(() => expect(mockAcceptLoanCounter).toHaveBeenCalledWith('sg-1', 'o2', 'test-token'))
    // Accepting removes it from the open listing the same way — refreshed here too.
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['loanOffers', 'sg-1'] }))
  })

  it('surfaces a countered loan offer from GET /loans — reachable with no dialog ever opened this session — and accepts it from there', async () => {
    const COUNTERED: LoanOfferResult = {
      offerId: 'o-open', playerId: 'p-m1', playerName: 'Meio Um', status: 'COUNTERED',
      managedClubIsOwner: true,
      ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 75, counterMayFaceOwner: true,
      refusal: 'TERMS_REJECTED',
    }
    mockGetLoanOffers.mockResolvedValue([COUNTERED])
    mockAcceptLoanCounter.mockResolvedValue({ ...COUNTERED, status: 'ACCEPTED' })
    renderWithProviders()

    expect(await screen.findByText(/empréstimos com contraproposta pendente/i)).toBeInTheDocument()
    expect(screen.getByText(/75% de salário/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))
    await waitFor(() => expect(mockAcceptLoanCounter).toHaveBeenCalledWith('sg-1', 'o-open', 'test-token'))
  })

  it('disables Aceitar on the pending-loan panel when the window is closed, but Desistir still withdraws it', async () => {
    mockGetSaveGame.mockResolvedValue({ ...SAVE_OPEN, transferWindowOpen: false })
    const COUNTERED: LoanOfferResult = {
      offerId: 'o-open', playerId: 'p-m1', playerName: 'Meio Um', status: 'COUNTERED',
      managedClubIsOwner: true,
      ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 75, counterMayFaceOwner: true,
      refusal: 'TERMS_REJECTED',
    }
    mockGetLoanOffers.mockResolvedValue([COUNTERED])
    mockWithdrawLoanOffer.mockResolvedValue({ ...COUNTERED, status: 'WITHDRAWN' })
    renderWithProviders()

    expect(await screen.findByText(/empréstimos com contraproposta pendente/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aceitar' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Desistir' }))
    await waitFor(() => expect(mockWithdrawLoanOffer).toHaveBeenCalledWith('sg-1', 'o-open', 'test-token'))
  })

  it('when accepting a counter is refused because the squad shrank since the offer, surfaces the error and keeps the offer in the panel', async () => {
    // acceptLoanCounter re-runs the departure guard against the CURRENT squad — a sale/release/
    // second loan after the counter but before the accept can make it fail with SQUAD_TOO_SMALL
    // even though the counter itself was fine. The offer is honest (still open, still COUNTERED)
    // and must stay reachable — the manager might fix the squad and try again, or withdraw.
    const COUNTERED: LoanOfferResult = {
      offerId: 'o-open', playerId: 'p-m1', playerName: 'Meio Um', status: 'COUNTERED',
      managedClubIsOwner: true,
      ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 75, counterMayFaceOwner: true,
      refusal: 'TERMS_REJECTED',
    }
    mockGetLoanOffers.mockResolvedValue([COUNTERED])
    mockAcceptLoanCounter.mockRejectedValue(new ApiError('Squad too small', 'SQUAD_TOO_SMALL', 422))
    renderWithProviders()

    expect(await screen.findByText(/empréstimos com contraproposta pendente/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))

    expect(await screen.findByText('O elenco ficaria pequeno demais.')).toBeInTheDocument()
    // Still there: a failed accept never invalidates ['loanOffers', saveGameId], so the panel
    // keeps showing exactly the offer GET /loans returned — nothing silently dropped it.
    expect(screen.getByText(/empréstimos com contraproposta pendente/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aceitar' })).toBeInTheDocument()
  })

  it('marks a borrowed player in the squad list with who he is on loan from', async () => {
    const borrowed = makeOutfield('p-borrowed', 'Emprestado Um', 'MID')
    borrowed.onLoanFromClubName = 'Avaí FC'
    mockGetSquad.mockResolvedValue({ ...squad, players: [...squad.players, borrowed] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Emprestado Um'))
    // "de" (not "(...)") so the badge reads unambiguously as who lent HIM, not who he was lent to.
    expect(screen.getByText('Emprestado de Avaí FC')).toBeInTheDocument()
  })

  it('shows a "Listado" badge for a listed player and lets the user unlist them', async () => {
    mockUnlistPlayer.mockResolvedValue({ playerId: LISTED.id, transferListed: false, listedReason: null })
    renderWithProviders()
    await waitFor(() => screen.getByText('Listado Um'))
    expect(screen.getAllByText('Listado').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByText('Listado Um'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /retirar da lista/i }))
    await waitFor(() => expect(mockUnlistPlayer).toHaveBeenCalledWith('sg-1', LISTED.id, 'test-token'))
  })

  it('lets the user list a not-yet-listed player for transfer', async () => {
    mockListPlayer.mockResolvedValue({ playerId: GK.id, transferListed: true, listedReason: 'OWNER' })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: /listar para transferência/i }))
    await waitFor(() => expect(mockListPlayer).toHaveBeenCalledWith('sg-1', GK.id, 'test-token'))
  })

  it('shows a role selector scoped to the ASSIGNED SLOT position for a selected outfield player', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    fireEvent.click(pickBtn('defensor um'))
    openRoleMenu('Defensor Um')
    const select = screen.getByRole('combobox', { name: /função de defensor um/i })
    expect(select).toBeInTheDocument()
    // DEF1 carries LATERAL_ESQUERDO, so the default 4-3-3's "le" slot is its exact match —
    // options are every archetype valid for LATERAL_ESQUERDO (enum order), not a coarse DEF
    // band, and use the canonical backend RoleArchetype labels.
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(['Lateral', 'Ala', 'Falso lateral', 'Ala ofensivo', 'Ala invertido'])
  })

  it('shows a role selector scoped to the ASSIGNED SLOT position for a selected midfielder', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Meio Um'))
    fireEvent.click(pickBtn('meio um'))
    openRoleMenu('Meio Um')
    const select = screen.getByRole('combobox', { name: /função de meio um/i })
    expect(select).toBeInTheDocument()
    // MID1 has no detailed position, so it lands in the first free MID slot of the default
    // 4-3-3 (VOLANTE) — options are every archetype valid for VOLANTE, in enum order.
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(['Primeiro volante', 'Volante armador', 'Terceiro zagueiro', 'Volante ala', 'Volante de chegada'])
  })

  it('shows a role selector scoped to the ASSIGNED SLOT position for a selected attacker', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Atacante Um'))
    fireEvent.click(pickBtn('atacante um'))
    openRoleMenu('Atacante Um')
    const select = screen.getByRole('combobox', { name: /função de atacante um/i })
    expect(select).toBeInTheDocument()
    // ATT1 has no detailed position, so it lands in the first free ATT slot of the default
    // 4-3-3 (PONTA_DIREITA) — options are every archetype valid for PONTA_DIREITA.
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(['Ponta', 'Armador aberto', 'Atacante por dentro'])
  })

  it('rehydrates the saved formation and starters on load', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID4.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: ATT1.id, role: 'POACHER', instruction: null },
        { playerId: ATT2.id, role: 'POACHER', instruction: null },
      ],
      bench: [],
    })
    renderWithProviders()
    // Once the saved lineup resolves, the formation select reflects it (not the 4-3-3 default).
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-4-2'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('GOL')
    // DEF1 is a LATERAL_ESQUERDO, so slot assignment puts him in the left-back slot: "LE".
    expect(pickBtn('defensor um')).toHaveTextContent('LE')
    // A defender with no detailed position takes whichever back-four slot is left over.
    expect(pickBtn('defensor dois')).toHaveTextContent(/^(LD|ZAG|LE)$/)
    // A complete, all-eligible XI → saving is enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('loads an ineligible saved starter, blocks saving, and lets the user swap them out', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-2', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: SUSPENDED.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: ATT1.id, role: 'POACHER', instruction: null },
        { playerId: ATT2.id, role: 'POACHER', instruction: null },
        { playerId: ATT3.id, role: 'POACHER', instruction: null },
      ],
      bench: [],
    })
    renderWithProviders()
    const banido = await screen.findByRole('button', { name: /escalar banido um/i })
    // Loaded as a starter despite being suspended (a position label, not "+"), and left
    // clickable so it can be removed.
    await waitFor(() => expect(banido).not.toHaveTextContent('+'))
    expect(banido).not.toBeDisabled()
    // Saving is blocked with a message naming the unavailable player.
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/indisponível/i)
    expect(screen.getByRole('alert')).toHaveTextContent(/Banido Um/i)
    // The user resolves it themselves — no auto-substitution.
    fireEvent.click(banido)
    await waitFor(() => expect(banido).toHaveTextContent('+'))
  })

  it('auto-selects the best XI for the formation', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    // A complete, valid 4-3-3 is picked, so saving is enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
    // The higher-rated keeper (Goleiro Dois, 75) beats Goleiro Teste (72), so he starts…
    expect(pickBtn('goleiro dois')).toHaveTextContent('GOL')
    // …and Goleiro Teste is left unpicked.
    expect(pickBtn('goleiro teste')).toHaveTextContent('+')
  })

  it('clears the selected squad', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('button', { name: /limpar/i }))
    expect(pickBtn('goleiro dois')).toHaveTextContent('+')
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
  })

  it('keeps bench numbering stable when a substitute is removed', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    // Auto-pick a full 4-3-3 (Goleiro Teste always loses the GK slot to the higher-rated
    // Goleiro Dois), then send three extras (their bands are full) to the bench. Auto-pick's
    // natural-first assignment doesn't guarantee exactly which MID/ATT players it keeps when
    // several tie on overall — find one leftover of each band generically instead of hardcoding.
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    const leftOutMid = [MID1, MID2, MID3, MID4].find(p => pickBtn(p.name).textContent === '+')!
    const leftOutAtt = [ATT1, ATT2, ATT3, ATT4].find(p => pickBtn(p.name).textContent === '+')!
    fireEvent.click(pickBtn('goleiro teste'))       // GK band full → Sub 1
    fireEvent.click(pickBtn(leftOutMid.name))        // MID band full → Sub 2
    fireEvent.click(pickBtn(leftOutAtt.name))        // ATT band full → Sub 3
    expect(pickBtn('goleiro teste')).toHaveTextContent('Sub 1')
    expect(pickBtn(leftOutMid.name)).toHaveTextContent('Sub 2')
    expect(pickBtn(leftOutAtt.name)).toHaveTextContent('Sub 3')

    // Remove the first substitute — the others keep their numbers (no renumbering).
    fireEvent.click(pickBtn('goleiro teste'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('+')
    expect(pickBtn(leftOutMid.name)).toHaveTextContent('Sub 2')
    expect(pickBtn(leftOutAtt.name)).toHaveTextContent('Sub 3')

    // The freed slot (Sub 1) is reused by the next player sent to the bench.
    fireEvent.click(pickBtn('goleiro teste'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('Sub 1')
  })

  it('opens a details dialog with the attribute breakdown when a player name is clicked', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    // The CM-style attribute board renders the grouped attributes.
    expect(within(dialog).getByText('Velocidade')).toBeInTheDocument()
    expect(within(dialog).getByText('Técnico')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/OVR 72/)).toBeInTheDocument()
  })

  it('shows contract details on the Contrato tab', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('tab', { name: /contrato/i }))
    expect(within(dialog).getByText(/Salário pedido/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/2 ano\(s\)/)).toBeInTheDocument()
  })

  it('marks the Contrato tab with a red dot when the contract is ending', async () => {
    const endingGk = { ...GK, id: 'p-gk-end', name: 'Goleiro Fim', contractYears: 1 }
    mockGetSquad.mockResolvedValue({ players: [endingGk, ...ALL_OUTFIELD], primaryColor: '#f7d417', secondaryColor: '#111111' })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Fim'))
    fireEvent.click(screen.getByText('Goleiro Fim'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByTitle('Contrato terminando')).toBeInTheDocument()
  })

  it('renders a fitness bar and injury severity for players', async () => {
    renderWithProviders()
    expect(await screen.findByLabelText('Condição 45%')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /lesionado \(leve\) — 3 jogo/i })).toBeInTheDocument()
  })

  it('sends the selected bench players when saving the lineup', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    // The MID line is full after auto-pick, so a MID-band player left out of the XI can't
    // start — he drops to the bench (one unified list, no separate bench control).
    const leftOutMid = [MID1, MID2, MID3, MID4].find(p => pickBtn(p.name).textContent === '+')!
    fireEvent.click(pickBtn(leftOutMid.name))
    expect(pickBtn(leftOutMid.name)).toHaveTextContent(/sub/i)
    fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

    await waitFor(() =>
      expect(setLineup).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({ bench: expect.arrayContaining([expect.objectContaining({ playerId: leftOutMid.id })]) }),
        'test-token',
      ),
    )
  })

  it('shows a morale mood chip per player and a team dressing-room chip', async () => {
    mockGetSquad.mockResolvedValue({
      players: [{ ...MID1, id: 'p-happy', name: 'Feliz', morale: 2 }],
      primaryColor: '#f7d417',
      secondaryColor: '#111111',
    })
    renderWithProviders()
    // Verify per-player mood chip renders in Plantel tab
    await screen.findByText('Feliz')
    expect(screen.getByLabelText('Contente')).toBeInTheDocument()
    // Team "dressing room" chip lives in the Táticas tab, verified there
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
    expect(screen.getByText(/Elenco:/)).toBeInTheDocument()
  })

  it('removes auto-picked XI players from the bench when "Selecionar melhores" is clicked', async () => {
    // Seed a saved lineup that puts MID1 on the bench before auto-pick runs.
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-bench', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null },
        { playerId: ATT1.id, role: 'POACHER', instruction: null },
        { playerId: ATT2.id, role: 'POACHER', instruction: null },
        { playerId: ATT3.id, role: 'POACHER', instruction: null },
      ],
      bench: [{ playerId: MID4.id, role: 'BOX_TO_BOX', instruction: null }],
    })

    renderWithProviders()
    // Wait for the saved lineup to hydrate (MID4 on bench). Scoped to the selection button
    // (not `getByText`) because "Goleiro Teste" is also a starter option inside the
    // Táticas de jogo tab's (mounted-but-hidden) penalty/free-kick/corner-taker selects.
    await waitFor(() => pickBtn('goleiro teste'))

    // Click auto-pick — this will place GK2, DEF1-4, MID1-3, ATT1-3 in the XI.
    // GK2 was not in the bench, but if GK2 had been on the bench it must be evicted.
    // More critically: any player now in the XI must not appear in the bench payload.
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    // The XI is valid so saving should be enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

    await waitFor(() => expect(mockSetLineup).toHaveBeenCalled())

    // Extract the bench argument from the setLineup call.
    const callArgs = mockSetLineup.mock.calls[0]
    const payload = callArgs[2] as { starters: { playerId: string }[]; bench: { playerId: string }[] }
    const starterIds = payload.starters.map((s: { playerId: string }) => s.playerId)
    const benchIds = payload.bench.map((b: { playerId: string }) => b.playerId)

    // No player in the XI should also appear in the bench — mutual-exclusion invariant.
    const overlap = starterIds.filter((id: string) => benchIds.includes(id))
    expect(overlap).toHaveLength(0)
  })

  it('shows the personal-instruction select with Nenhuma + 6 options for a selected outfield player (mobile)', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true)
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    // Select the player so the dialog carries its lineup controls…
    fireEvent.click(pickBtn('defensor um'))
    // …then open the details dialog via the player's name button. (Query by button role, not
    // getByText: the name now also appears in the Táticas tab's mounted-but-hidden taker selects.)
    fireEvent.click(screen.getByRole('button', { name: 'Defensor Um' }))
    const dialog = await screen.findByRole('dialog')

    const select = within(dialog).getByRole('combobox', { name: /instrução pessoal de defensor um/i })
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual([
      'Nenhuma',
      'Infiltrar na área', 'Chutar de longe', 'Cruzar mais',
      'Segurar posição', 'Marcação dura', 'Driblar mais',
    ])
  })

  it('hides the personal-instruction select for a goalkeeper (mobile)', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true)
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(pickBtn('goleiro teste'))
    fireEvent.click(screen.getByRole('button', { name: 'Goleiro Teste' }))
    const dialog = await screen.findByRole('dialog')

    // The role selector still shows (a GK has a role), but no personal-instruction selector.
    expect(within(dialog).getByRole('combobox', { name: /função de goleiro teste/i })).toBeInTheDocument()
    expect(within(dialog).queryByRole('combobox', { name: /instrução pessoal de/i })).not.toBeInTheDocument()
  })

  it('includes the chosen personal instruction in the setLineup payload (mobile)', async () => {
    vi.mocked(useIsMobile).mockReturnValue(true)
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))

    // Build a valid 4-3-3 XI so saving is enabled.
    const xi = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of xi) {
      fireEvent.click(pickBtn(player.name))
    }

    // Assign an instruction to Defensor Um via the details dialog.
    fireEvent.click(screen.getByRole('button', { name: 'Defensor Um' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(
      within(dialog).getByRole('combobox', { name: /instrução pessoal de defensor um/i }),
      { target: { value: 'MARCACAO_DURA' } },
    )
    // Close the dialog, then save. findByRole waits out the dialog's exit transition, which
    // otherwise keeps the app root aria-hidden and the save button momentarily inaccessible.
    fireEvent.click(within(dialog).getByRole('button', { name: /fechar/i }))
    fireEvent.click(await screen.findByRole('button', { name: /salvar escalação/i }))

    await waitFor(() =>
      expect(mockSetLineup).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({
          starters: expect.arrayContaining([
            expect.objectContaining({ playerId: DEF1.id, instruction: 'MARCACAO_DURA' }),
          ]),
        }),
        'test-token',
      ),
    )
    // Every other selected player carries a null instruction (none chosen).
    const payload = mockSetLineup.mock.calls[0][2] as { starters: { playerId: string; instruction: string | null }[] }
    expect(payload.starters.find(s => s.playerId === GK.id)?.instruction).toBeNull()
  })

  it('sends a slotIndex permutation (GK at 0) for the starters and null for the bench', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(pickBtn('goleiro teste')) // bands full → bench
    fireEvent.click(screen.getByRole('button', { name: /salvar escala/i }))

    await waitFor(() => expect(mockSetLineup).toHaveBeenCalled())
    const req = mockSetLineup.mock.calls[0][2]
    const slotIndexes = req.starters.map((s: { slotIndex?: number | null }) => s.slotIndex)
    expect([...slotIndexes].sort((a, b) => (a ?? -1) - (b ?? -1)))
      .toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    const gkStarter = req.starters.find((s: { playerId: string }) => s.playerId === GK2.id)
    expect(gkStarter?.slotIndex).toBe(0)
    expect(req.bench.every((s: { slotIndex?: number | null }) => s.slotIndex == null)).toBe(true)
  })

  it('rehydrates explicit slot assignments from the saved lineup onto the pitch', async () => {
    // Deliberately cross-line: MID1 is saved into an ATT slot (index 9 in 4-3-3).
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-3', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null, slotIndex: 0 },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null, slotIndex: 1 },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null, slotIndex: 4 },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 5 },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 6 },
        { playerId: ATT1.id, role: 'POACHER', instruction: null, slotIndex: 7 },
        { playerId: ATT2.id, role: 'POACHER', instruction: null, slotIndex: 8 },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 9 },
        { playerId: ATT3.id, role: 'POACHER', instruction: null, slotIndex: 10 },
      ],
      bench: [],
    })
    const { container } = renderWithProviders()
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-3-3'))
    // The pitch (mounted under the hidden Táticas panel) placed MID1 at slot 9.
    expect(container.querySelector('[data-slot-index="9"]')).toHaveTextContent('Meio Um')
    // Cross-line arrangements are legal: saving stays enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('blocks saving when a saved starter with a persisted slotIndex is no longer in the squad', async () => {
    // Regression: the verbatim hydration branch (every starter carries a slotIndex) must drop a
    // departed player's slot the same way the legacy fallback branch already does (via its own
    // `.filter(Boolean)`), so validate's permutation-size check catches it — instead of `toStarter`
    // throwing on a ghost id at save time.
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-4', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null, slotIndex: 0 },
        { playerId: DEF1.id, role: 'FULLBACK', instruction: null, slotIndex: 1 },
        { playerId: DEF2.id, role: 'FULLBACK', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'FULLBACK', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULLBACK', instruction: null, slotIndex: 4 },
        { playerId: MID1.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 5 },
        { playerId: MID2.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 6 },
        { playerId: MID3.id, role: 'BOX_TO_BOX', instruction: null, slotIndex: 7 },
        { playerId: ATT1.id, role: 'POACHER', instruction: null, slotIndex: 8 },
        { playerId: ATT2.id, role: 'POACHER', instruction: null, slotIndex: 9 },
        // Transferred out since the lineup was saved — not in `squad.players` anymore.
        { playerId: 'p-departed', role: 'POACHER', instruction: null, slotIndex: 10 },
      ],
      bench: [],
    })
    renderWithProviders()
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-3-3'))
    // The departed player's slot never lands in slotByPlayerId, so the permutation is short by
    // one — validate blocks saving instead of letting a save attempt reach `toStarter` and throw.
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/posição no campo/i)
  })

  it('blocks saving when a selected starter drops out of the squad after hydration', async () => {
    // Regression: distinct from the test above, which covers a *saved* lineup that already
    // referenced a departed player at hydration time (Task 7's fix filters that in the
    // hydration effect). Here the XI is built normally while the squad is fully intact, and
    // a starter departs only AFTER hydration — e.g. Layout's advance-day handler invalidates
    // the ['squad', ...] query while this page stays mounted with them already selected.
    // Nothing re-runs the hydration effect, so `selected`/`slotByPlayerId` still reference
    // them; only the refetched `players` array drops them. `validate` must catch this itself.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <MemoryRouter initialEntries={['/squad?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SquadPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByText('Goleiro Teste'))

    // Build a valid, fully-slotted XI the normal way (auto-pick is deterministic: GK2,
    // DEF1-4, MID1-3, ATT1-3 for the default 4-3-3 — see the comment on the bench test above).
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // Simulate the squad refetching without DEF1 (transferred/released/contract expired)
    // the same way Layout.tsx's `invalidateQueries({ queryKey: ['squad'] })` on advance-day
    // would, while DEF1 is still a selected starter.
    mockGetSquad.mockResolvedValueOnce({
      ...squad,
      players: squad.players.filter(p => p.id !== DEF1.id),
    })
    await act(async () => {
      await qc.invalidateQueries({ queryKey: ['squad', 'sg-1', 'club-1'] })
    })

    // DEF1 is gone from the roster, but `selected` and `slotByPlayerId` still hold them —
    // validate must still block saving (assigned.size drops below 11) instead of leaving
    // `canSave` true and letting `toStarter` crash on the missing player at save time.
    await waitFor(() => expect(screen.queryByText('Defensor Um')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/posição no campo/i)
  })

  it('dragging on the tactics pitch swaps the two players in the save payload', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const tokenAt = (slot: number) => pitch.querySelector(`[data-slot-index="${slot}"]`)!
    // Token text is "«shirt number»«surname»"; every fixture name is unique, so the suffix
    // identifies the player.
    const idOfToken = (text: string | null) =>
      squad.players.find(p => text?.endsWith(p.name))!.id
    // The auto-picked 4-3-3 puts DEF1 (the only LATERAL_ESQUERDO) in slot 4 — the LE slot.
    expect(tokenAt(4).textContent).toContain('Defensor Um')
    const toText = tokenAt(9).textContent
    const toName = squad.players.find(p => toText?.endsWith(p.name))!.name

    // Drag slot 4 (left-back, center 336/474 in 4-3-3) onto slot 9 (striker, 200/192).
    fireEvent.pointerDown(tokenAt(4), { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerMove(tokenAt(4), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(tokenAt(4), { pointerId: 1, clientX: 200, clientY: 192 })

    // The two tokens swapped on the pitch (shirt numbers follow the slot, so match by name)…
    await waitFor(() => expect(tokenAt(9).textContent).toContain('Defensor Um'))
    expect(tokenAt(4).textContent).toContain(toName)
    // …and the lateral in the striker slot is flagged out of position.
    expect(tokenAt(9).querySelector('[data-out-of-position="true"]')).not.toBeNull()

    // Saving from the pitch's own button sends the swapped permutation.
    const saveButtons = screen.getAllByRole('button', { name: /salvar escala/i })
    fireEvent.click(saveButtons[saveButtons.length - 1])
    await waitFor(() => expect(mockSetLineup).toHaveBeenCalled())
    const req = mockSetLineup.mock.calls[0][2]
    const slotOf = (playerId: string) =>
      req.starters.find((s: { playerId: string }) => s.playerId === playerId)?.slotIndex
    const slotIndexes = req.starters.map((s: { slotIndex?: number | null }) => s.slotIndex)
    expect([...slotIndexes].sort((a, b) => (a ?? -1) - (b ?? -1)))
      .toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    // The dragged pair really exchanged indexes.
    expect(slotOf(DEF1.id)).toBe(9)
    expect(slotOf(idOfToken(toText))).toBe(4)
  })

  it('re-derives slot assignments when the formation changes and keeps saving enabled', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    const xi = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of xi) {
      fireEvent.click(pickBtn(player.name))
    }
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // Under slot-based validation a 4-DEF XI still fits 3-5-2: the 4th defender lands in a
    // midfield slot (out of position, penalised — but saveable).
    fireEvent.change(screen.getByLabelText(/formação/i), { target: { value: '3-5-2' } })
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('SquadPage — slot picker and Função/Foco selects', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSquad.mockResolvedValue(squad)
    mockGetLineup.mockResolvedValue(null)
    mockSetLineup.mockResolvedValue({ lineupId: 'l-1', clubId: 'club-1', formation: '4-3-3', starters: [], bench: [] })
    vi.mocked(getTactics).mockResolvedValue({
      mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL',
      penaltyTakerId: null, freekickTakerId: null, cornerTakerId: null,
    })
    mockGetFinances.mockResolvedValue({ balance: 20000000, transactions: [] })
    mockGetClubs.mockResolvedValue(CLUBS)
    mockGetSaveGame.mockResolvedValue(SAVE_OPEN)
    mockGetLoanOffers.mockResolvedValue([])
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  function mockRect() {
    return {
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect
  }

  it('tapping a pitch slot opens the slot picker dialog naming the slot', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    // Slot 4 ("le", LATERAL_ESQUERDO) center in the default 4-3-3 layout.
    const slot4 = pitch.querySelector('[data-slot-index="4"]')!

    fireEvent.pointerDown(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerUp(slot4, { pointerId: 1, clientX: 336, clientY: 474 })

    expect(await screen.findByText('Quem joga aqui? (LE)')).toBeInTheDocument()
  })

  it('does not open the picker when the pointer interaction resolves as a drag', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    const slot4 = pitch.querySelector('[data-slot-index="4"]')!

    fireEvent.pointerDown(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerMove(slot4, { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(slot4, { pointerId: 1, clientX: 200, clientY: 192 })

    // The drag still moved the player (existing behavior) but no picker opened.
    await waitFor(() => expect(pitch.querySelector('[data-slot-index="9"]')).toHaveTextContent('Defensor Um'))
    expect(screen.queryByText(/Quem joga aqui\?/)).not.toBeInTheDocument()
  })

  it('picking a player from the slot picker swaps them with the slot\'s current occupant', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    const slot4 = pitch.querySelector('[data-slot-index="4"]')! // LE — auto-pick always puts DEF1 here
    expect(slot4).toHaveTextContent('Defensor Um')
    const slot9 = pitch.querySelector('[data-slot-index="9"]')!
    const otherName = squad.players.find(p => slot9.textContent?.endsWith(p.name))!.name

    fireEvent.pointerDown(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerUp(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText(otherName))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // The two players swapped places rather than one dropping off the pitch.
    expect(pitch.querySelector('[data-slot-index="4"]')).toHaveTextContent(otherName)
    expect(pitch.querySelector('[data-slot-index="9"]')).toHaveTextContent('Defensor Um')
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('picking a BENCH player from the slot picker swaps them into the slot, sending the displaced starter to the bench spot the picked player vacated', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    // Auto-pick fills only 3 of the 6 MID-eligible fixtures; send one of the leftovers to the
    // bench explicitly so we have a genuine bench player to pick.
    const midPool = [MID1, MID2, MID3, MID4, LOW_FITNESS, LISTED]
    const leftOutMid = midPool.find(p => pickBtn(p.name).textContent === '+')!
    fireEvent.click(pickBtn(leftOutMid.name))
    expect(pickBtn(leftOutMid.name)).toHaveTextContent('Sub 1')

    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
    const pitch = screen.getByTestId('pitch')
    // Slot 5 is the first MID slot in the default 4-3-3 (GK=0, DEF=1-4, MID=5-7, ATT=8-10) —
    // tie-break-agnostic: whichever MID starter auto-pick placed there is our occupant.
    const slot5 = pitch.querySelector('[data-slot-index="5"]')!
    const occupantName = midPool.find(p => p.id !== leftOutMid.id && slot5.textContent?.endsWith(p.name))!.name

    fireEvent.pointerDown(slot5, { pointerId: 1, clientX: 0, clientY: 0 })
    fireEvent.pointerUp(slot5, { pointerId: 1, clientX: 0, clientY: 0 })
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText(leftOutMid.name))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // The bench player now starts in that slot...
    expect(pitch.querySelector('[data-slot-index="5"]')).toHaveTextContent(leftOutMid.name)
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // ...and the displaced starter takes over the bench spot the picked player vacated (Sub 1) —
    // nobody drops out of the squad. (Plantel's status buttons are only accessible-by-role while
    // that tab panel is the visible one, so switch back before querying them.)
    fireEvent.click(screen.getByRole('tab', { name: 'Plantel' }))
    expect(pickBtn(occupantName)).toHaveTextContent('Sub 1')
    expect(pickBtn(leftOutMid.name)).not.toHaveTextContent(/sub/i)
  })

  it('does not lose the displaced occupant when the bench is already full (no-ops instead of dropping them)', async () => {
    // A dedicated, larger squad: exactly enough DEF/ATT/GK to fill their bands with nothing
    // left over, plus 13 MID-eligible players — 3 starters, 9 to fill the bench solid, and one
    // more left completely unpicked to attempt the doomed pick.
    const mk = (id: string, name: string, position: 'GK' | 'DEF' | 'MID' | 'ATT'): PlayerResponse => ({
      id, name, position, age: 24, overall: 70, pace: 60, finishing: 50, passing: 55,
      tackling: 50, goalkeeping: position === 'GK' ? 70 : 10, stamina: 65, ...STATE,
    })
    // Zero-padded indexes: "escalar bf meio 1" would otherwise ambiguously match "BF Meio 1",
    // "BF Meio 10", "BF Meio 11" and "BF Meio 12" under pickBtn's unanchored name regex.
    const pad = (i: number) => String(i).padStart(2, '0')
    const gk = mk('bf-gk', 'BF Goleiro', 'GK')
    const defs = Array.from({ length: 4 }, (_, i) => mk(`bf-d${i}`, `BF Def ${pad(i)}`, 'DEF'))
    const atts = Array.from({ length: 3 }, (_, i) => mk(`bf-a${i}`, `BF Ata ${pad(i)}`, 'ATT'))
    const mids = Array.from({ length: 13 }, (_, i) => mk(`bf-m${i}`, `BF Meio ${pad(i)}`, 'MID'))
    mockGetSquad.mockResolvedValueOnce({
      players: [gk, ...defs, ...atts, ...mids], primaryColor: '#f7d417', secondaryColor: '#111111',
    })

    renderWithProviders()
    await waitFor(() => screen.getByText('BF Goleiro'))

    // Build the exact 4-3-3 XI: GK, 4 DEF, 3 ATT, and the first 3 MID fixtures.
    for (const p of [gk, ...defs, ...atts, ...mids.slice(0, 3)]) {
      fireEvent.click(pickBtn(p.name))
    }
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // Send the next 9 MID fixtures to the bench — it fills solid (Sub 1..Sub 9), no holes.
    const benched = mids.slice(3, 12)
    for (const p of benched) fireEvent.click(pickBtn(p.name))
    for (let i = 0; i < benched.length; i++) {
      expect(pickBtn(benched[i].name)).toHaveTextContent(`Sub ${i + 1}`)
    }
    // One MID fixture (mids[12]) is left completely unpicked — neither a starter nor benched.
    const extra = mids[12]
    expect(pickBtn(extra.name)).toHaveTextContent('+')

    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
    const pitch = screen.getByTestId('pitch')
    const slot5 = pitch.querySelector('[data-slot-index="5"]')! // a MID starter's slot
    const occupantName = mids.slice(0, 3).find(p => slot5.textContent?.endsWith(p.name))!.name

    // Attempt to pick the fully-unplaced extra player into the occupied MID slot: the occupant
    // has nowhere to go (not the picked player's own bench spot — there isn't one — and the
    // bench has no hole and is already at its 9-slot cap).
    fireEvent.pointerDown(slot5, { pointerId: 1, clientX: 0, clientY: 0 })
    fireEvent.pointerUp(slot5, { pointerId: 1, clientX: 0, clientY: 0 })
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText(extra.name))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // Nobody vanished: the original occupant is still exactly where they were...
    expect(pitch.querySelector('[data-slot-index="5"]')).toHaveTextContent(occupantName)
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // ...the extra player is still simply unpicked (not silently added anywhere), and the whole
    // bench is untouched. (Switch back to Plantel — its status buttons aren't accessible-by-role
    // while that panel is hidden.)
    fireEvent.click(screen.getByRole('tab', { name: 'Plantel' }))
    expect(pickBtn(extra.name)).toHaveTextContent('+')
    for (let i = 0; i < benched.length; i++) {
      expect(pickBtn(benched[i].name)).toHaveTextContent(`Sub ${i + 1}`)
    }
  })

  it('does not flag a player as out-of-position on the pitch when only their ALTERNATE position matches the slot they were picked into', async () => {
    const altPlayer: PlayerResponse = {
      ...makeOutfield('p-alt', 'Alt Lateral', 'DEF'),
      detailedPosition: 'ZAGUEIRO',
      altPositions: ['LATERAL_DIREITO'],
    }
    mockGetSquad.mockResolvedValueOnce({ ...squad, players: [...squad.players, altPlayer] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    const slot1 = pitch.querySelector('[data-slot-index="1"]')! // LD — accepts LATERAL_DIREITO only
    fireEvent.pointerDown(slot1, { pointerId: 1, clientX: 0, clientY: 0 })
    fireEvent.pointerUp(slot1, { pointerId: 1, clientX: 0, clientY: 0 })
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByText('Alt Lateral'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(pitch.querySelector('[data-slot-index="1"]')).toHaveTextContent('Alt Lateral')
    // Natural via altPositions (LATERAL_DIREITO), even though the player's primary detailed
    // position (ZAGUEIRO) is not accepted by this slot — no amber warn tint.
    expect(pitch.querySelector('[data-slot-index="1"] [data-out-of-position="true"]')).toBeNull()
  })

  it('"Selecionar melhores" gives each auto-picked player their best familiar archetype and its default focus', async () => {
    // DEF1 (LATERAL_ESQUERDO) is familiar (++) with WINGBACK — a non-clone copy so the shared
    // module-level DEF1 fixture used by every other test in this file stays untouched.
    const def1WithFamiliar = { ...DEF1, familiarRoles: ['WINGBACK++'] }
    mockGetSquad.mockResolvedValueOnce({
      ...squad,
      players: squad.players.map(p => (p.id === DEF1.id ? def1WithFamiliar : p)),
    })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    openRoleMenu('Defensor Um')

    // WINGBACK ("Ala") beats the vanilla FULLBACK ("Lateral") default because it's familiar.
    expect(screen.getByRole('combobox', { name: /função de defensor um/i })).toHaveValue('WINGBACK')
    // WINGBACK's default focus for LATERAL_ESQUERDO (MATRIX order) is BALANCED.
    expect(screen.getByRole('combobox', { name: /foco de defensor um/i })).toHaveValue('BALANCED')
  })

  it('falls back to the vanilla default archetype when the player has no familiar role for their slot', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    openRoleMenu('Defensor Um')

    // DEF1 (in the base fixtures) carries no familiarRoles at all → FULLBACK, the vanilla
    // default for LATERAL_ESQUERDO.
    expect(screen.getByRole('combobox', { name: /função de defensor um/i })).toHaveValue('FULLBACK')
  })

  it('resets Foco to the new archetype\'s default when Função changes', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    fireEvent.click(pickBtn('defensor um')) // lands in the exact "le" (LATERAL_ESQUERDO) slot
    openRoleMenu('Defensor Um')

    const funcaoSelect = screen.getByRole('combobox', { name: /função de defensor um/i })
    const focoSelect = screen.getByRole('combobox', { name: /foco de defensor um/i })
    // FULLBACK's default focus for LATERAL_ESQUERDO is BALANCED.
    expect(focoSelect).toHaveValue('BALANCED')

    fireEvent.change(funcaoSelect, { target: { value: 'ATTACKING_WINGBACK' } })
    // ATTACKING_WINGBACK's default focus for LATERAL_ESQUERDO (MATRIX order) is SUPPORT.
    expect(focoSelect).toHaveValue('SUPPORT')
  })

  it('sends the chosen focus in the setLineup payload', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    fireEvent.click(pickBtn('defensor um'))
    openRoleMenu('Defensor Um')
    fireEvent.change(
      screen.getByRole('combobox', { name: /função de defensor um/i }),
      { target: { value: 'ATTACKING_WINGBACK' } },
    )
    // Close the menu (Escape) — while open, MUI marks the rest of the app aria-hidden, which
    // hides the Plantel tab from the accessibility tree — then switch back to it: the pitch's
    // pickBtn-equivalent squad table is only reachable there.
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    fireEvent.click(await screen.findByRole('tab', { name: /plantel/i }))
    // Complete the XI so saving is enabled.
    for (const player of [GK, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]) {
      fireEvent.click(pickBtn(player.name))
    }
    fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

    await waitFor(() =>
      expect(mockSetLineup).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({
          starters: expect.arrayContaining([
            expect.objectContaining({ playerId: DEF1.id, role: 'ATTACKING_WINGBACK', focus: 'SUPPORT' }),
          ]),
        }),
        'test-token',
      ),
    )
  })

  it('renders Salvar escalação before Selecionar melhores, and not at the bottom of Plantel', async () => {
    renderWithProviders()
    await screen.findByText('Goleiro Teste')
    // Only one save button is reachable: the Táticas panel is mounted but `hidden`, so its copy
    // is out of the accessibility tree.
    expect(screen.getAllByRole('button', { name: 'Salvar escalação' })).toHaveLength(1)
    const labels = screen.getAllByRole('button').map(b => b.textContent)
    expect(labels.indexOf('Salvar escalação')).toBeLessThan(labels.indexOf('Selecionar melhores'))
  })

  it('shows the squad morale chip in the Táticas tab, not in Plantel', async () => {
    renderWithProviders()
    await screen.findByText('Goleiro Teste')
    expect(screen.queryByText(/Elenco:/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
    expect(screen.getByText(/Elenco:/)).toBeInTheDocument()
  })

  it('no longer shows Função and Foco columns in the Plantel table', async () => {
    renderWithProviders()
    await screen.findByText('Goleiro Teste')
    expect(screen.queryByRole('columnheader', { name: 'Função' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Foco' })).not.toBeInTheDocument()
  })

  it('right-clicking a pitch token opens the Função/Foco menu for that player', async () => {
    renderWithProviders()
    await screen.findByText('Goleiro Teste')
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))
    // Auto-pick puts the higher-rated keeper (Goleiro Dois, OVR 75) in slot 0.
    const gkToken = screen.getByTestId('pitch').querySelector('[data-slot-index="0"]')!
    fireEvent.contextMenu(gkToken, { clientX: 100, clientY: 200 })
    expect(await screen.findByRole('combobox', { name: 'Função de Goleiro Dois' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Foco de Goleiro Dois' })).toBeInTheDocument()
  })

  it('allows an XI with no recognised goalkeeper', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    // Slot 0 (GK) center in the default 4-3-3 layout.
    const slot0 = pitch.querySelector('[data-slot-index="0"]')!
    fireEvent.pointerDown(slot0, { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(slot0, { pointerId: 1, clientX: 200, clientY: 564 })

    // Put an unpicked outfielder in goal; the displaced keeper drops to the bench, leaving a
    // full XI with zero goalkeepers.
    fireEvent.click(await screen.findByTestId('slot-picker-row-p-m4'))
    await waitFor(() => expect(pitch.querySelector('[data-slot-index="0"]')).toHaveTextContent('Meio Quatro'))
    // Wait for the dialog's exit transition to actually finish: MUI keeps the rest of the tree
    // aria-hidden until then, and a role-based query issued mid-transition would find nothing.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // Assert on the Plantel tab, where the validation alert lives — a keeper-less XI is now
    // legal, so no alert renders. (Do NOT match on /goleiro/i across the document: the hidden
    // Plantel panel still lists both keepers' names, which `queryByText` would happily find.)
    fireEvent.click(screen.getByRole('tab', { name: 'Plantel' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar escalação' })).toBeEnabled()
  })

  it('blocks an XI containing two goalkeepers', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('tab', { name: /táticas de jogo/i }))

    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue(mockRect())
    // Slot 4 ("le", LATERAL_ESQUERDO) center — an outfield slot.
    const slot4 = pitch.querySelector('[data-slot-index="4"]')!
    fireEvent.pointerDown(slot4, { pointerId: 1, clientX: 336, clientY: 474 })
    fireEvent.pointerUp(slot4, { pointerId: 1, clientX: 336, clientY: 474 })

    // Auto-pick fields Goleiro Dois (OVR 75) in goal; adding Goleiro Teste outfield makes two.
    fireEvent.click(await screen.findByTestId('slot-picker-row-p-gk'))
    expect(await screen.findByText('No máximo 1 goleiro na escalação')).toBeInTheDocument()
  })
})
