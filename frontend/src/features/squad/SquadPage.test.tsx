import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SquadPage } from './SquadPage'
import { AuthProvider } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { PlayerResponse, SquadResponse } from '../../api/types'

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
}))

vi.mock('../../lib/useIsMobile', () => ({ useIsMobile: vi.fn(() => false) }))

import { getSquad, getLineup, setLineup, getTactics, renewContract, listPlayer, unlistPlayer, setTraining } from '../../api/career'

const mockGetSquad = vi.mocked(getSquad)
const mockGetLineup = vi.mocked(getLineup)
const mockSetLineup = vi.mocked(setLineup)
const mockRenewContract = vi.mocked(renewContract)
const mockListPlayer = vi.mocked(listPlayer)
const mockUnlistPlayer = vi.mocked(unlistPlayer)
const mockSetTraining = vi.mocked(setTraining)

// `as const` narrows injurySeverity to the literal 'NONE' so it satisfies
// PlayerResponse's union type (not widened to string) when spread into fixtures.
const STATE = { detailedPosition: null, detailedPositionLabel: null, form: 0, morale: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0, injurySeverity: 'NONE' as const, eligible: true, role: null, contractYears: 2, value: 1500000, wageDemand: 90000, fitness: 100, attributes: [
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

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderWithProviders(saveGameId = 'sg-1', clubId = 'club-1') {
  const qc = makeQueryClient()
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

  it('renders the formation pitch with the selected XI', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null }, { playerId: DEF2.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null }, { playerId: DEF4.id, role: 'FULL_BACK', instruction: null },
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

  it('lets the user set a training focus/intensity and saves them for the whole squad', async () => {
    mockSetTraining.mockResolvedValue(squad)
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))

    fireEvent.change(
      screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` }),
      { target: { value: 'REFLEXOS' } },
    )
    fireEvent.change(
      screen.getByRole('combobox', { name: `Intensidade de treino de ${GK.name}` }),
      { target: { value: 'INTENSA' } },
    )
    fireEvent.click(screen.getByRole('button', { name: /salvar treino/i }))

    await waitFor(() =>
      expect(mockSetTraining).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({
          assignments: expect.arrayContaining([
            expect.objectContaining({ playerId: GK.id, focus: 'REFLEXOS', intensity: 'INTENSA' }),
          ]),
        }),
        'test-token',
      ),
    )
  })

  it('offers all twelve trainable attributes plus Nenhum as training focus options', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))

    const select = screen.getByRole('combobox', { name: `Foco de treino de ${GK.name}` })
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual([
      'Nenhum',
      'Velocidade', 'Finalização', 'Passe', 'Desarme', 'Reflexos', 'Fôlego',
      'Cabeceio', 'Cruzamento', 'Marcação', 'Drible', 'Visão de jogo', 'Força',
    ])
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

  it('writes the saved training result directly into the squad cache without an extra fetch', async () => {
    mockSetTraining.mockResolvedValue(squad)
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    expect(mockGetSquad).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('tab', { name: 'Treino' }))
    fireEvent.click(screen.getByRole('button', { name: /salvar treino/i }))

    await waitFor(() => expect(mockSetTraining).toHaveBeenCalled())
    expect(mockGetSquad).toHaveBeenCalledTimes(1)
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

  it('blocks selecting more attackers than the formation allows', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Atacante Um'))

    // Default formation 4-3-3 allows exactly 3 attackers.
    for (const player of [ATT1, ATT2, ATT3]) {
      fireEvent.click(pickBtn(player.name))
    }
    expect(pickBtn('atacante um')).toHaveTextContent('AT')

    // The attacking line is full, so a 4th attacker can't take a starting slot — the click
    // sends them to the bench instead.
    fireEvent.click(pickBtn('atacante quatro'))
    expect(pickBtn('atacante quatro')).toHaveTextContent(/sub/i)
  })

  it('keeps button disabled when fewer than 11 are selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    fireEvent.click(pickBtn('goleiro teste'))

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    expect(saveBtn).toBeDisabled()
  })

  it('blocks selecting a second goalkeeper', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Dois'))

    fireEvent.click(pickBtn('goleiro teste'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('GK')

    // Only one keeper can start, so clicking a second keeper can't take the GK slot — they go
    // to the bench instead.
    fireEvent.click(pickBtn('goleiro dois'))
    expect(pickBtn('goleiro dois')).toHaveTextContent(/sub/i)
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

  it('shows a role selector for a selected outfield player', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))
    fireEvent.click(pickBtn('defensor um'))
    const select = screen.getByRole('combobox', { name: /função de defensor um/i })
    expect(select).toBeInTheDocument()
    // Options are scoped to the player's position band (DEF), not all roles, and shown with
    // readable Portuguese labels (the underlying value stays the enum).
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(['Zagueiro', 'Lateral', 'Zagueiro construtor'])
  })

  it('rehydrates the saved formation and starters on load', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null },
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
    expect(pickBtn('goleiro teste')).toHaveTextContent('GK')
    // DEF1 carries a detailed position (LATERAL_ESQUERDO) → its slot button shows the detailed "LE".
    expect(pickBtn('defensor um')).toHaveTextContent('LE')
    // A defender without a detailed position falls back to the coarse slot abbr "DC".
    expect(pickBtn('defensor dois')).toHaveTextContent('DC')
    // A complete, all-eligible XI → saving is enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('loads an ineligible saved starter, blocks saving, and lets the user swap them out', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-2', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null },
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
    expect(pickBtn('goleiro dois')).toHaveTextContent('GK')
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
    // Auto-pick a full 4-3-3, then send three extras (their bands are full) to the bench.
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(pickBtn('goleiro teste'))   // GK band full → Sub 1
    fireEvent.click(pickBtn('meio quatro'))     // MID band full → Sub 2
    fireEvent.click(pickBtn('atacante quatro')) // ATT band full → Sub 3
    expect(pickBtn('goleiro teste')).toHaveTextContent('Sub 1')
    expect(pickBtn('meio quatro')).toHaveTextContent('Sub 2')
    expect(pickBtn('atacante quatro')).toHaveTextContent('Sub 3')

    // Remove the first substitute — the others keep their numbers (no renumbering).
    fireEvent.click(pickBtn('goleiro teste'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('+')
    expect(pickBtn('meio quatro')).toHaveTextContent('Sub 2')
    expect(pickBtn('atacante quatro')).toHaveTextContent('Sub 3')

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
    // "Selecionar melhores" with 4-3-3 picks: GK2, DEF1-4, MID1-3, ATT1-3.
    // MID4 ("Meio Quatro", id "p-m4") is eligible and won't be in the auto-picked XI.
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    // The MID line is full after auto-pick, so clicking Meio Quatro can't start him — he
    // drops to the bench (one unified list, no separate bench control).
    fireEvent.click(pickBtn('meio quatro'))
    expect(pickBtn('meio quatro')).toHaveTextContent(/sub/i)
    fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

    await waitFor(() =>
      expect(setLineup).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({ bench: expect.arrayContaining([expect.objectContaining({ playerId: 'p-m4' })]) }),
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
    // Team "dressing room" chip (unique prefix)
    expect(await screen.findByText(/Elenco:/)).toBeInTheDocument()
    // At least the player row chip renders the Contente mood
    expect(screen.getAllByText(/Contente/).length).toBeGreaterThanOrEqual(1)
  })

  it('removes auto-picked XI players from the bench when "Selecionar melhores" is clicked', async () => {
    // Seed a saved lineup that puts MID1 on the bench before auto-pick runs.
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-bench', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER', instruction: null },
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null },
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
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null, slotIndex: 1 },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null, slotIndex: 4 },
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
        { playerId: DEF1.id, role: 'FULL_BACK', instruction: null, slotIndex: 1 },
        { playerId: DEF2.id, role: 'FULL_BACK', instruction: null, slotIndex: 2 },
        { playerId: DEF3.id, role: 'FULL_BACK', instruction: null, slotIndex: 3 },
        { playerId: DEF4.id, role: 'FULL_BACK', instruction: null, slotIndex: 4 },
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
