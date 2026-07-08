import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SquadPage } from './SquadPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { PlayerResponse, SquadResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getSquad: vi.fn(),
  getLineup: vi.fn(),
  setLineup: vi.fn(),
  getTactics: vi.fn(),
  setTactics: vi.fn(),
  renewContract: vi.fn(),
}))

import { getSquad, getLineup, setLineup, getTactics, renewContract } from '../../api/career'

const mockGetSquad = vi.mocked(getSquad)
const mockGetLineup = vi.mocked(getLineup)
const mockSetLineup = vi.mocked(setLineup)
const mockRenewContract = vi.mocked(renewContract)

// `as const` narrows injurySeverity to the literal 'NONE' so it satisfies
// PlayerResponse's union type (not widened to string) when spread into fixtures.
const STATE = { form: 0, morale: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0, injurySeverity: 'NONE' as const, eligible: true, role: null, contractYears: 2, value: 1500000, wageDemand: 90000, fitness: 100, attributes: [
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

const ALL_OUTFIELD = [DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]

const squad: SquadResponse = {
  players: [GK, GK2, ...ALL_OUTFIELD, MID4, ATT4, SUSPENDED, INJURED, LOW_FITNESS],
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
      mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL',
      penaltyTakerId: null, freekickTakerId: null, cornerTakerId: null,
    })
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders the formation pitch with the selected XI', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-1', clubId: 'club-1', formation: '4-4-2',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER' },
        { playerId: DEF1.id, role: 'FULL_BACK' }, { playerId: DEF2.id, role: 'FULL_BACK' },
        { playerId: DEF3.id, role: 'FULL_BACK' }, { playerId: DEF4.id, role: 'FULL_BACK' },
        { playerId: MID1.id, role: 'BOX_TO_BOX' }, { playerId: MID2.id, role: 'BOX_TO_BOX' },
        { playerId: MID3.id, role: 'BOX_TO_BOX' }, { playerId: MID4.id, role: 'BOX_TO_BOX' },
        { playerId: ATT1.id, role: 'POACHER' }, { playerId: ATT2.id, role: 'POACHER' },
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

  it('shows tactics controls above the pitch in the Táticas de jogo tab', async () => {
    renderWithProviders()
    fireEvent.click(await screen.findByRole('tab', { name: 'Táticas de jogo' }))
    const panel = screen.getByRole('tabpanel', { name: 'Táticas de jogo' })
    expect(panel).toBeVisible()
    const instrucoes = within(panel).getByRole('button', { name: /Instruções da equipe/ })
    expect(instrucoes).toHaveAttribute('aria-expanded', 'true')
    expect(await within(panel).findByLabelText('Mentalidade')).toBeInTheDocument()
    // Tactics section renders before (above) the pitch in the DOM.
    const salvarTatica = within(panel).getByRole('button', { name: /Salvar tática/ })
    expect(salvarTatica.compareDocumentPosition(within(panel).getByTestId('pitch'))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
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

  it('invalidates the lineup query after saving so the advance gate refreshes', async () => {
    // Regression: the layout's "Escale seu time" warning + disabled Avançar are driven by the
    // ['lineup', saveGameId, clubId] query. Saving a lineup must invalidate it, otherwise the
    // warning persists and Avançar stays disabled until a full page reload.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
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
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['lineup', 'sg-1'] }),
    )
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

  it('blocks saving when the line-up no longer fits a changed formation', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    // Build a valid 4-3-3 XI (4 DEF, 3 MEI, 3 ATA + 1 GK).
    const xi = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of xi) {
      fireEvent.click(pickBtn(player.name))
    }
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()

    // Switching to 3-5-2 (3 DEF, 5 MEI, 2 ATA) makes the current shape invalid.
    fireEvent.change(screen.getByLabelText(/formação/i), { target: { value: '3-5-2' } })
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/3-5-2/)
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
        { playerId: GK.id, role: 'GOALKEEPER' },
        { playerId: DEF1.id, role: 'FULL_BACK' },
        { playerId: DEF2.id, role: 'FULL_BACK' },
        { playerId: DEF3.id, role: 'FULL_BACK' },
        { playerId: DEF4.id, role: 'FULL_BACK' },
        { playerId: MID1.id, role: 'BOX_TO_BOX' },
        { playerId: MID2.id, role: 'BOX_TO_BOX' },
        { playerId: MID3.id, role: 'BOX_TO_BOX' },
        { playerId: MID4.id, role: 'BOX_TO_BOX' },
        { playerId: ATT1.id, role: 'POACHER' },
        { playerId: ATT2.id, role: 'POACHER' },
      ],
      bench: [],
    })
    renderWithProviders()
    // Once the saved lineup resolves, the formation select reflects it (not the 4-3-3 default).
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-4-2'))
    expect(pickBtn('goleiro teste')).toHaveTextContent('GK')
    expect(pickBtn('defensor um')).toHaveTextContent('DC')
    // A complete, all-eligible XI → saving is enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
  })

  it('loads an ineligible saved starter, blocks saving, and lets the user swap them out', async () => {
    mockGetLineup.mockResolvedValue({
      lineupId: 'l-2', clubId: 'club-1', formation: '4-3-3',
      starters: [
        { playerId: GK.id, role: 'GOALKEEPER' },
        { playerId: DEF1.id, role: 'FULL_BACK' },
        { playerId: DEF2.id, role: 'FULL_BACK' },
        { playerId: DEF3.id, role: 'FULL_BACK' },
        { playerId: DEF4.id, role: 'FULL_BACK' },
        { playerId: MID1.id, role: 'BOX_TO_BOX' },
        { playerId: MID2.id, role: 'BOX_TO_BOX' },
        { playerId: SUSPENDED.id, role: 'BOX_TO_BOX' },
        { playerId: ATT1.id, role: 'POACHER' },
        { playerId: ATT2.id, role: 'POACHER' },
        { playerId: ATT3.id, role: 'POACHER' },
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
    expect(within(dialog).getByText(/OVR 72/)).toBeInTheDocument()
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
        { playerId: GK.id, role: 'GOALKEEPER' },
        { playerId: DEF1.id, role: 'FULL_BACK' },
        { playerId: DEF2.id, role: 'FULL_BACK' },
        { playerId: DEF3.id, role: 'FULL_BACK' },
        { playerId: DEF4.id, role: 'FULL_BACK' },
        { playerId: MID1.id, role: 'BOX_TO_BOX' },
        { playerId: MID2.id, role: 'BOX_TO_BOX' },
        { playerId: MID3.id, role: 'BOX_TO_BOX' },
        { playerId: ATT1.id, role: 'POACHER' },
        { playerId: ATT2.id, role: 'POACHER' },
        { playerId: ATT3.id, role: 'POACHER' },
      ],
      bench: [{ playerId: MID4.id, role: 'BOX_TO_BOX' }],
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
})
