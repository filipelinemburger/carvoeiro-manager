import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ClubViewPage } from './ClubViewPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { ClubSummary, PlayerResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  getClubs: vi.fn(),
  getSquad: vi.fn(),
  makeOffer: vi.fn(),
}))

import { getSaveGame, getClubs, getSquad, makeOffer } from '../../api/career'

const MANAGED_CLUB_ID = 'club-1'
const OTHER_CLUB_ID = 'club-9'

const SAVE = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'c-1', competitionName: 'Catarinense',
  managedClubId: MANAGED_CLUB_ID, managedClubName: 'Criciúma', currentRound: 0, totalRounds: 18,
  transferWindowOpen: true,
}

const clubs: ClubSummary[] = [
  { id: MANAGED_CLUB_ID, name: 'Criciúma EC', commonName: 'Criciúma', shortName: 'CRI',
    primaryColor: '#f7d417', secondaryColor: '#111111', reputation: 74, squadSize: 1 },
  { id: OTHER_CLUB_ID, name: 'Rival FC', commonName: 'Rival', shortName: 'RIV',
    primaryColor: '#aa0000', secondaryColor: '#ffffff', reputation: 70, squadSize: 1 },
]

function player(overrides: Partial<PlayerResponse> = {}): PlayerResponse {
  return {
    id: 'p1', name: 'Alvo Um', position: 'ATT', age: 24, overall: 80, pace: 80, finishing: 78,
    passing: 65, tackling: 30, goalkeeping: 10, stamina: 75, form: 0, morale: 70, yellowCards: 0,
    suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 3,
    value: 2688000, wageDemand: 60000, fitness: 100, injurySeverity: 'NONE', attributes: [],
    ...overrides,
  }
}

function renderPage(clubId = OTHER_CLUB_ID) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={[`/club?saveGameId=sg-1&clubId=${clubId}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <ClubViewPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('ClubViewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE)
    vi.mocked(getClubs).mockResolvedValue(clubs)
    vi.mocked(getSquad).mockResolvedValue({
      players: [player()], primaryColor: '#aa0000', secondaryColor: '#ffffff',
    })
  })

  it('renders the roster with market value and wage demand', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText(/Elenco — Rival/)).toBeInTheDocument()
    // Value and wage demand come formatted as BRL (non-breaking space after R$).
    expect(screen.getByText(/2\.688\.000/)).toBeInTheDocument()
    expect(screen.getByText(/60\.000/)).toBeInTheDocument()
    expect(getSquad).toHaveBeenCalledWith('sg-1', OTHER_CLUB_ID, 'test-token')
  })

  it('shows a "Listado" chip for a transfer-listed player', async () => {
    vi.mocked(getSquad).mockResolvedValue({
      players: [player({ transferListed: true, listedReason: 'FINANCIAL' })],
      primaryColor: '#aa0000', secondaryColor: '#ffffff',
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Listado')).toBeInTheDocument()
  })

  it('opens player details when the name is clicked', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Alvo Um' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/OVR 80/)).toBeInTheDocument()
  })

  it('opens the offer dialog and fires makeOffer with the drafted terms', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: MANAGED_CLUB_ID, fromClubName: 'Criciúma',
      toClubId: OTHER_CLUB_ID, toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2400000,
      counterAmount: 3000000, status: 'COUNTERED', round: 0, wage: 60000, contractYears: 3,
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))

    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.change(within(dialog).getByLabelText(/Salário para Alvo Um/i), { target: { value: '65000' } })
    fireEvent.click(within(dialog).getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() => expect(makeOffer).toHaveBeenCalledWith(
      'sg-1',
      { playerId: 'p1', amount: 2400000, wage: 65000, contractYears: 3 },
      'test-token',
    ))
    // The negotiation outcome is surfaced to the user.
    expect(await screen.findByText(/Contraproposta/)).toBeInTheDocument()
  })

  it('falls back to the suggested terms when the offer fields are left empty', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: MANAGED_CLUB_ID, fromClubName: 'Criciúma',
      toClubId: OTHER_CLUB_ID, toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2688000,
      counterAmount: null, status: 'ACCEPTED', round: 0, wage: 60000, contractYears: 3,
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() => expect(makeOffer).toHaveBeenCalledWith(
      'sg-1',
      { playerId: 'p1', amount: 2688000, wage: 60000, contractYears: 3 },
      'test-token',
    ))
  })

  it('hides "Fazer proposta" when viewing the managed club', async () => {
    vi.mocked(getSquad).mockResolvedValue({
      players: [player({ id: 'mp1', name: 'Meu Jogador' })], primaryColor: '#f7d417', secondaryColor: '#111111',
    })
    renderPage(MANAGED_CLUB_ID)
    await waitFor(() => expect(screen.getByText('Meu Jogador')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /fazer proposta/i })).not.toBeInTheDocument()
  })
})
