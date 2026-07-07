import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TransferMarketPage } from './TransferMarketPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  getMarket: vi.fn(),
  getOffers: vi.fn(),
  makeOffer: vi.fn(),
  acceptCounter: vi.fn(),
  withdrawOffer: vi.fn(),
  respondToOffer: vi.fn(),
  getSquad: vi.fn(),
  getFreeAgents: vi.fn(),
  signFreeAgent: vi.fn(),
}))

import { getSaveGame, getMarket, getOffers, makeOffer, respondToOffer, getFreeAgents, getSquad } from '../../api/career'

const SAVE_OPEN = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'c-1', competitionName: 'Catarinense',
  managedClubId: 'club-1', managedClubName: 'Criciúma', currentRound: 0, totalRounds: 18,
  transferWindowOpen: true,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/transfers?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TransferMarketPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('TransferMarketPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_OPEN)
    vi.mocked(getOffers).mockResolvedValue([])
    vi.mocked(getMarket).mockResolvedValue([
      { playerId: 'p1', name: 'Alvo Um', position: 'ATT', age: 24, overall: 80,
        clubId: 'club-9', clubName: 'Rival FC', value: 3000000, wage: 50000, wageDemand: 60000 },
    ])
    vi.mocked(getFreeAgents).mockResolvedValue([])
    vi.mocked(getSquad).mockResolvedValue({ players: [] } as never)
  })

  /** The market list lives on the "Jogadores disponíveis" tab. */
  function openMarketTab() {
    fireEvent.click(screen.getByRole('tab', { name: /jogadores dispon/i }))
  }

  it('lists market players and shows the AI response to an offer', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2400000,
      counterAmount: 3300000, status: 'COUNTERED', round: 0, wage: null, contractYears: null,
    })

    renderPage()
    openMarketTab()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Rival FC')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() => expect(screen.getByText(/Contraproposta/i)).toBeInTheDocument())
    expect(makeOffer).toHaveBeenCalledWith('sg-1', expect.objectContaining({ playerId: 'p1', amount: 2400000 }), 'test-token')
  })

  it('shows a closed-window banner and disables proposing when the window is closed', async () => {
    vi.mocked(getSaveGame).mockResolvedValue({ ...SAVE_OPEN, transferWindowOpen: false })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/fechada/i))
    openMarketTab()
    expect(await screen.findByRole('button', { name: /fazer proposta/i })).toBeDisabled()
  })

  it('sends wage and length with the fee', async () => {
    vi.mocked(getMarket).mockResolvedValue([
      { playerId: 'p1', name: 'Alvo Um', position: 'ATT', age: 24, overall: 80,
        clubId: 'club-9', clubName: 'Rival FC', value: 3000000, wage: 50000, wageDemand: 60000 },
    ])
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2400000,
      counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null,
    })

    renderPage()
    openMarketTab()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.change(screen.getByLabelText(/Salário para Alvo Um/i), { target: { value: '65000' } })
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() =>
      expect(makeOffer).toHaveBeenCalledWith(
        'sg-1',
        expect.objectContaining({ wage: expect.any(Number), contractYears: expect.any(Number) }),
        'test-token',
      ),
    )
  })

  it('shows incoming bids and accepts one', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null },
    ])
    vi.mocked(respondToOffer).mockResolvedValue({
      id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
      toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
      counterAmount: null, status: 'ACCEPTED', round: 0, wage: null, contractYears: null,
    })

    renderPage()
    // Tab 0 (Propostas recebidas) is active by default; wait for the incoming bid to load.
    expect(await screen.findByText(/Meu Jogador/)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /propostas recebidas/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))
    await waitFor(() =>
      expect(respondToOffer).toHaveBeenCalledWith('sg-1', 'in1', { action: 'ACCEPT', counterAmount: null }, 'test-token'),
    )
  })

  it('surfaces the result after negotiating an incoming offer (regression: no response shown)', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null },
    ])
    // Backend resolves the counter inline: here the AI accepts the user's counter.
    vi.mocked(respondToOffer).mockResolvedValue({
      id: 'in1', playerId: 'Meu Jogador', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
      toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
      counterAmount: 2500000, status: 'ACCEPTED', round: 0, wage: null, contractYears: null,
    })

    renderPage()
    expect(await screen.findByText(/Meu Jogador/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))

    // The negotiation result must be shown to the user, not silently swallowed.
    expect(await screen.findByText(/Proposta aceita/i)).toBeInTheDocument()
  })

  it('shows free agents on the Agentes livres tab', async () => {
    vi.mocked(getFreeAgents).mockResolvedValue([
      { playerId: 'fa1', name: 'Agente Livre', position: 'MID', age: 29, overall: 70, wageDemand: 80000 },
    ] as never)
    renderPage()
    fireEvent.click(screen.getByRole('tab', { name: /agentes livres/i }))
    expect(await screen.findByText('Agente Livre')).toBeInTheDocument()
  })

  it('opens player details when an incoming proposal name is clicked', async () => {
    vi.mocked(getOffers).mockResolvedValue([
      { id: 'in1', playerId: 'mp1', playerName: 'Meu Jogador', fromClubId: 'club-9', fromClubName: 'Rival FC',
        toClubId: 'club-1', toClubName: 'Criciúma', direction: 'INCOMING', amount: 2000000,
        counterAmount: null, status: 'PENDING', round: 0, wage: null, contractYears: null },
    ])
    vi.mocked(getSquad).mockResolvedValue({
      players: [
        { id: 'mp1', name: 'Meu Jogador', position: 'ATT', age: 25, overall: 78, pace: 80, finishing: 75,
          passing: 70, tackling: 30, goalkeeping: 10, stamina: 75, form: 0, yellowCards: 0,
          suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 3, wageDemand: 90000 },
      ],
    } as never)

    renderPage()
    // Click the player's name; retry until the squad has loaded and the dialog can open.
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Meu Jogador' }))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    expect(within(screen.getByRole('dialog')).getByText(/OVR 78/)).toBeInTheDocument()
  })
})
