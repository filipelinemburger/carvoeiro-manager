import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TrainingTab } from './TrainingTab'
import { AuthProvider } from '../../../auth/AuthContext'
import type { PlayerResponse, SquadResponse } from '../../../api/types'
import type { TrainingAssignmentsState } from './bulkTraining'

vi.mock('../../../api/career', () => ({ setTraining: vi.fn() }))
import { setTraining } from '../../../api/career'
const mockSetTraining = vi.mocked(setTraining)

const player = (id: string, name: string, detailedPosition: PlayerResponse['detailedPosition'],
                position: PlayerResponse['position']): PlayerResponse =>
  ({ id, name, position, detailedPosition, altPositions: null, overall: 70 } as unknown as PlayerResponse)

const PLAYERS = [
  player('gk1', 'Alisson', 'GOLEIRO', 'GK'),
  player('gk2', 'Airton', 'GOLEIRO', 'GK'),
  player('cb1', 'Rodrigo', 'ZAGUEIRO', 'DEF'),
]

const ASSIGNMENTS: TrainingAssignmentsState = {
  gk1: { focus: null, intensity: 'NORMAL' },
  gk2: { focus: null, intensity: 'NORMAL' },
  cb1: { focus: null, intensity: 'NORMAL' },
}

function renderTab(onChange = vi.fn(), assignments = ASSIGNMENTS) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const utils = render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TrainingTab
            players={PLAYERS}
            saveGameId="sg-1"
            clubId="club-1"
            assignments={assignments}
            onChange={onChange}
            playerNameButton={p => <span>{p.name}</span>}
          />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
  return { ...utils, onChange, qc }
}

// A stateful wrapper, for scenarios that need `onChange` to actually feed back into `assignments`
// (e.g. saving right after a change) — `renderTab` above holds `assignments` fixed, since most
// cases only care about the single onChange call it produces.
function renderControlledTab() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  function Controlled() {
    const [assignments, setAssignments] = useState<TrainingAssignmentsState>(ASSIGNMENTS)
    return (
      <TrainingTab
        players={PLAYERS}
        saveGameId="sg-1"
        clubId="club-1"
        assignments={assignments}
        onChange={setAssignments}
        playerNameButton={p => <span>{p.name}</span>}
      />
    )
  }
  const utils = render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <Controlled />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
  return { ...utils, qc }
}

beforeEach(() => { vi.clearAllMocks() })

describe('TrainingTab', () => {
  it('renders one row per player with a focus and an intensity control', () => {
    renderTab()
    expect(screen.getByRole('combobox', { name: 'Foco de treino de Alisson' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Intensidade de treino de Rodrigo' })).toBeInTheDocument()
  })

  it('reports a per-player focus change', () => {
    const { onChange } = renderTab()
    fireEvent.change(screen.getByRole('combobox', { name: 'Foco de treino de Alisson' }), {
      target: { value: 'REFLEXOS' },
    })
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      gk1: { focus: 'REFLEXOS', intensity: 'NORMAL' },
    }))
  })

  it('saves every player on Salvar treino', async () => {
    mockSetTraining.mockResolvedValue({ players: [] } as unknown as SquadResponse)
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: 'Salvar treino' }))
    await waitFor(() => expect(mockSetTraining).toHaveBeenCalledWith(
      'sg-1', 'club-1',
      { assignments: [
        { playerId: 'gk1', focus: null, intensity: 'NORMAL' },
        { playerId: 'gk2', focus: null, intensity: 'NORMAL' },
        { playerId: 'cb1', focus: null, intensity: 'NORMAL' },
      ] },
      expect.anything(),
    ))
  })

  // Moved from SquadPage.test.tsx: the options offered by the focus select.
  it('offers all twelve trainable attributes plus Nenhum as training focus options', () => {
    renderTab()
    const select = screen.getByRole('combobox', { name: 'Foco de treino de Alisson' })
    const options = within(select).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual([
      'Nenhum',
      'Velocidade', 'Finalização', 'Passe', 'Desarme', 'Reflexos', 'Fôlego',
      'Cabeceio', 'Cruzamento', 'Marcação', 'Drible', 'Visão de jogo', 'Força',
    ])
  })

  // Moved from SquadPage.test.tsx: changing focus AND intensity for the same player, then
  // saving, must send BOTH new values together — needs a stateful parent (renderControlledTab)
  // since `renderTab` above keeps `assignments` fixed regardless of `onChange`.
  it('saves a combined focus and intensity change for the same player', async () => {
    mockSetTraining.mockResolvedValue({ players: [] } as unknown as SquadResponse)
    renderControlledTab()

    fireEvent.change(screen.getByRole('combobox', { name: 'Foco de treino de Alisson' }), {
      target: { value: 'REFLEXOS' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Intensidade de treino de Alisson' }), {
      target: { value: 'INTENSA' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar treino' }))

    await waitFor(() => expect(mockSetTraining).toHaveBeenCalledWith(
      'sg-1', 'club-1',
      expect.objectContaining({
        assignments: expect.arrayContaining([
          { playerId: 'gk1', focus: 'REFLEXOS', intensity: 'INTENSA' },
        ]),
      }),
      expect.anything(),
    ))
  })

  // Moved from SquadPage.test.tsx: a successful save writes straight into the squad cache
  // (write-through) instead of relying on a refetch.
  it('writes the saved training result directly into the squad cache', async () => {
    const savedSquad = { players: [] } as unknown as SquadResponse
    mockSetTraining.mockResolvedValue(savedSquad)
    const { qc } = renderTab()

    fireEvent.click(screen.getByRole('button', { name: 'Salvar treino' }))

    await waitFor(() => expect(qc.getQueryData(['squad', 'sg-1', 'club-1'])).toBe(savedSquad))
  })
})
