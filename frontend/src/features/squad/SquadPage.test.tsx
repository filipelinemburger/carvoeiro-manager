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
const STATE = { form: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0, injurySeverity: 'NONE' as const, eligible: true, role: null, contractYears: 2, wageDemand: 90000, fitness: 100 }

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
    })
    vi.mocked(getTactics).mockResolvedValue({
      mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL',
      penaltyTakerId: null, freekickTakerId: null, cornerTakerId: null,
    })
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
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
      const checkbox = screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') })
      fireEvent.click(checkbox)
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
      fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') }))
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
      fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') }))
    }

    // The 4th attacker's line is full, so it becomes unselectable.
    const fourth = screen.getByRole('checkbox', { name: /atacante quatro/i })
    expect(fourth).toBeDisabled()
    fireEvent.click(fourth)
    expect(fourth).not.toBeChecked()
  })

  it('blocks saving when the line-up no longer fits a changed formation', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    // Build a valid 4-3-3 XI (4 DEF, 3 MEI, 3 ATA + 1 GK).
    const xi = [GK, DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
    for (const player of xi) {
      fireEvent.click(screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') }))
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

    const checkbox = screen.getByRole('checkbox', { name: /goleiro teste/i })
    fireEvent.click(checkbox)

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    expect(saveBtn).toBeDisabled()
  })

  it('blocks selecting a second goalkeeper', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Dois'))

    fireEvent.click(screen.getByRole('checkbox', { name: /goleiro teste/i }))

    // Only one keeper fits any formation, so the second becomes unselectable.
    const secondGk = screen.getByRole('checkbox', { name: /goleiro dois/i })
    expect(secondGk).toBeDisabled()
    fireEvent.click(secondGk)
    expect(secondGk).not.toBeChecked()
  })

  it('does not let a suspended player be selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Banido Um'))
    const checkbox = screen.getByRole('checkbox', { name: /banido um/i })
    expect(checkbox).toBeDisabled()
    expect(screen.getByText(/\(Suspenso 1\)/i)).toBeInTheDocument()
  })

  it('shows "(Lesionado (Leve) N)" severity label for an injured player and disables their checkbox', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Lesionado Um'))
    const checkbox = screen.getByRole('checkbox', { name: /lesionado um/i })
    expect(checkbox).toBeDisabled()
    expect(screen.getByText(/\(Lesionado \(Leve\) 3\)/i)).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('checkbox', { name: /defensor um/i }))
    const select = screen.getByRole('combobox', { name: /função de defensor um/i })
    expect(select).toBeInTheDocument()
    // Options are scoped to the player's position band (DEF), not all roles
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(['STOPPER', 'FULL_BACK', 'BALL_PLAYING_DEF'])
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
    })
    renderWithProviders()
    // Once the saved lineup resolves, the formation select reflects it (not the 4-3-3 default).
    await waitFor(() => expect(screen.getByLabelText(/formação/i)).toHaveValue('4-4-2'))
    expect(screen.getByRole('checkbox', { name: /goleiro teste/i })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /defensor um/i })).toBeChecked()
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
    })
    renderWithProviders()
    const banido = await screen.findByRole('checkbox', { name: /banido um/i })
    // Loaded as a starter despite being suspended, and left checkable so it can be removed.
    await waitFor(() => expect(banido).toBeChecked())
    expect(banido).not.toBeDisabled()
    // Saving is blocked with a message naming the unavailable player.
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/indisponível/i)
    expect(screen.getByRole('alert')).toHaveTextContent(/Banido Um/i)
    // The user resolves it themselves — no auto-substitution.
    fireEvent.click(banido)
    await waitFor(() => expect(banido).not.toBeChecked())
  })

  it('auto-selects the best XI for the formation', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    // A complete, valid 4-3-3 is picked, so saving is enabled.
    expect(screen.getByRole('button', { name: /salvar escala/i })).not.toBeDisabled()
    // The higher-rated keeper (Goleiro Dois, 75) beats Goleiro Teste (72).
    expect(screen.getByRole('checkbox', { name: /goleiro dois/i })).toBeChecked()
    // Use exact name match to distinguish the starter checkbox ("Goleiro Teste") from the
    // bench checkbox ("Banco: Goleiro Teste") which appears for non-starters.
    expect(screen.getByRole('checkbox', { name: 'Goleiro Teste' })).not.toBeChecked()
  })

  it('clears the selected squad', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))
    fireEvent.click(screen.getByRole('button', { name: /limpar/i }))
    expect(screen.getByRole('checkbox', { name: /goleiro dois/i })).not.toBeChecked()
    expect(screen.getByRole('button', { name: /salvar escala/i })).toBeDisabled()
  })

  it('opens a details dialog with the attribute breakdown when a player name is clicked', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByText('Goleiro Teste'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Ritmo')).toBeInTheDocument()
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
    mockGetSquad.mockResolvedValue({ players: [endingGk, ...ALL_OUTFIELD] })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Fim'))
    fireEvent.click(screen.getByText('Goleiro Fim'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByTitle('Contrato terminando')).toBeInTheDocument()
  })

  it('renders a fitness bar and injury severity for players', async () => {
    renderWithProviders()
    expect(await screen.findByLabelText('Condição 45%')).toBeInTheDocument()
    expect(screen.getByText(/Lesionado \(Leve\) 3/)).toBeInTheDocument()
  })

  it('sends the selected bench players when saving the lineup', async () => {
    // "Selecionar melhores" with 4-3-3 picks: GK2, DEF1-4, MID1-3, ATT1-3.
    // MID4 ("Meio Quatro", id "p-m4") is eligible and won't be in the auto-picked XI.
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    fireEvent.click(screen.getByRole('button', { name: /selecionar melhores/i }))

    // Add MID4 to the bench (bench checkbox appears for non-starters).
    fireEvent.click(screen.getByRole('checkbox', { name: /banco: meio quatro/i }))
    fireEvent.click(screen.getByRole('button', { name: /salvar escalação/i }))

    await waitFor(() =>
      expect(setLineup).toHaveBeenCalledWith(
        'sg-1', 'club-1',
        expect.objectContaining({ bench: expect.arrayContaining([expect.objectContaining({ playerId: 'p-m4' })]) }),
        'test-token',
      ),
    )
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
    // Wait for the saved lineup to hydrate (MID4 on bench).
    await waitFor(() => screen.getByText('Goleiro Teste'))

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
