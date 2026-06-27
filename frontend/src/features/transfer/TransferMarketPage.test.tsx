import { render, screen, fireEvent, waitFor } from '@testing-library/react'
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
}))

import { getSaveGame, getMarket, getOffers, makeOffer, respondToOffer } from '../../api/career'

const SAVE_OPEN = {
  id: 'sg-1', seasonYear: 2026, competitionId: 'c-1', competitionName: 'Catarinense',
  managedClubId: 'club-1', managedClubName: 'Criciúma', currentRound: 0, totalRounds: 18,
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
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSaveGame).mockResolvedValue(SAVE_OPEN)
    vi.mocked(getOffers).mockResolvedValue([])
    vi.mocked(getMarket).mockResolvedValue([
      { playerId: 'p1', name: 'Alvo Um', position: 'ATT', age: 24, overall: 80,
        clubId: 'club-9', clubName: 'Rival FC', value: 3000000, wage: 50000, wageDemand: 60000 },
    ])
  })

  it('lists market players and shows the AI response to an offer', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: 'club-1', fromClubName: 'Criciúma',
      toClubId: 'club-9', toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2400000,
      counterAmount: 3300000, status: 'COUNTERED', round: 0, wage: null, contractYears: null,
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Rival FC')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.click(screen.getByRole('button', { name: /propor/i }))

    await waitFor(() => expect(screen.getByText(/Contraproposta/i)).toBeInTheDocument())
    expect(makeOffer).toHaveBeenCalledWith('sg-1', expect.objectContaining({ playerId: 'p1', amount: 2400000 }), 'test-token')
  })

  it('shows a closed-window banner and disables proposing when the window is closed', async () => {
    vi.mocked(getSaveGame).mockResolvedValue({ ...SAVE_OPEN, currentRound: 5 })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/fechada/i))
    expect(screen.getByRole('button', { name: /propor/i })).toBeDisabled()
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
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/Valor para Alvo Um/i), { target: { value: '2400000' } })
    fireEvent.change(screen.getByLabelText(/Salário para Alvo Um/i), { target: { value: '65000' } })
    fireEvent.click(screen.getByRole('button', { name: /propor/i }))

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
    await waitFor(() => expect(screen.getByText(/Propostas recebidas/i)).toBeInTheDocument())
    expect(screen.getByText(/Meu Jogador/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /aceitar venda/i }))
    await waitFor(() =>
      expect(respondToOffer).toHaveBeenCalledWith('sg-1', 'in1', { action: 'ACCEPT', counterAmount: null }, 'test-token'),
    )
  })
})
