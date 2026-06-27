import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SquadPage } from './SquadPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { PlayerResponse, SquadResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getSquad: vi.fn(),
  setLineup: vi.fn(),
  getTactics: vi.fn(),
  setTactics: vi.fn(),
  renewContract: vi.fn(),
}))

import { getSquad, setLineup, getTactics, renewContract } from '../../api/career'

const mockGetSquad = vi.mocked(getSquad)
const mockSetLineup = vi.mocked(setLineup)
const mockRenewContract = vi.mocked(renewContract)

const STATE = { form: 0, yellowCards: 0, suspensionRemaining: 0, eligible: true, role: null, contractYears: 2, wageDemand: 90000 }

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

const ALL_OUTFIELD = [DEF1, DEF2, DEF3, DEF4, MID1, MID2, MID3, ATT1, ATT2, ATT3]
// Extra outfield to allow selecting 11 without a GK
const ELEVEN_NO_GK = [...ALL_OUTFIELD, ATT4]
// Two GKs and 9 outfield players = 11 total
const ELEVEN_WITH_TWO_GK = [GK, GK2, DEF1, DEF2, DEF3, DEF4, MID1, MID2, ATT1, ATT2, ATT3]

const squad: SquadResponse = {
  players: [GK, GK2, ...ALL_OUTFIELD, ATT4, SUSPENDED],
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
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
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

  it('keeps button disabled and shows validation message when selection has no GK', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Defensor Um'))

    // Select 11 outfield players (no GK)
    for (const player of ELEVEN_NO_GK) {
      const checkbox = screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') })
      fireEvent.click(checkbox)
    }

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    // 11 players selected, no GK → button disabled, validation message about GK
    expect(saveBtn).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/goleiro/i)
  })

  it('keeps button disabled when fewer than 11 are selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))

    const checkbox = screen.getByRole('checkbox', { name: /goleiro teste/i })
    fireEvent.click(checkbox)

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    expect(saveBtn).toBeDisabled()
  })

  it('keeps button disabled and shows validation message when selection has 2 GKs', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Dois'))

    // Select 11 players including both GKs
    for (const player of ELEVEN_WITH_TWO_GK) {
      const checkbox = screen.getByRole('checkbox', { name: new RegExp(player.name, 'i') })
      fireEvent.click(checkbox)
    }

    const saveBtn = screen.getByRole('button', { name: /salvar escala/i })
    // 11 players selected but 2 GKs → button disabled, validation message about exactly 1 GK
    expect(saveBtn).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent(/exatamente 1 goleiro/i)
  })

  it('does not let a suspended player be selected', async () => {
    renderWithProviders()
    await waitFor(() => screen.getByText('Banido Um'))
    const checkbox = screen.getByRole('checkbox', { name: /banido um/i })
    expect(checkbox).toBeDisabled()
    expect(screen.getByText(/\(Suspenso 1\)/i)).toBeInTheDocument()
  })

  it('shows contract years and a renew control', async () => {
    mockRenewContract.mockResolvedValue({ playerId: GK.id, name: 'Goleiro Teste', wage: 90000, contractYears: 3 })
    renderWithProviders()
    await waitFor(() => screen.getByText('Goleiro Teste'))
    // Contract years column should be visible for the GK (contractYears: 2)
    expect(screen.getAllByText(/ano\(s\)/i).length).toBeGreaterThan(0)
    // Renovar button should be present
    expect(screen.getAllByRole('button', { name: /renovar/i }).length).toBeGreaterThan(0)
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
})
