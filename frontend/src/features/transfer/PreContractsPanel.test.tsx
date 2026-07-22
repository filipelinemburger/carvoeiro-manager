import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import PreContractsPanel from './PreContractsPanel'
import type { PreContractAgreement, PreContractEligible } from '../../api/types'

vi.mock('../../api/precontract', () => ({
  getPreContractEligible: vi.fn(),
  getPreContractAgreements: vi.fn(),
  makePreContractOffer: vi.fn(),
  acceptPreContractCounter: vi.fn(),
  withdrawPreContract: vi.fn(),
}))

vi.mock('../../api/career', () => ({
  renewContract: vi.fn(),
  getPlayer: vi.fn(),
}))

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'test-token' }) }))

import {
  getPreContractEligible, getPreContractAgreements, makePreContractOffer, acceptPreContractCounter,
} from '../../api/precontract'
import { renewContract, getPlayer } from '../../api/career'
import { getWatchlist, addToWatchlist } from '../../api/watchlist'

const ELIGIBLE_UM: PreContractEligible = {
  playerId: 'p1', name: 'Jogador Um', position: 'ATT', age: 27, overall: 78,
  clubName: 'Rival FC', wageDemand: 90000,
}

const THREAT_AGREEMENT: PreContractAgreement = {
  id: 'a1', playerId: 'p9', playerName: 'Ameaça Um', position: 'DEF', age: 24, overall: 74,
  fromClubName: 'Meu Clube', toClubName: 'Rival FC', wage: 50000, contractYears: 3,
  status: 'AGREED', incoming: true,
}

function renderPanel(props: Partial<{ windowOpen: boolean }> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <PreContractsPanel
        saveGameId="sg-1"
        token="test-token"
        managedClubId="club-1"
        windowOpen={props.windowOpen ?? true}
      />
    </QueryClientProvider>,
  )
}

describe('PreContractsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getPreContractEligible).mockResolvedValue([])
    vi.mocked(getPreContractAgreements).mockResolvedValue([])
    vi.mocked(getWatchlist).mockResolvedValue([])
  })

  it('renders eligible players with club name and demand, and the Propor button', async () => {
    vi.mocked(getPreContractEligible).mockResolvedValue([ELIGIBLE_UM])

    renderPanel()

    await screen.findByText('Jogador Um')
    expect(screen.getByText('Rival FC')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Propor' })).toBeInTheDocument()
  })

  it('opens read-only player details with a watch star when the eligible player name is clicked', async () => {
    vi.mocked(getPreContractEligible).mockResolvedValue([ELIGIBLE_UM])
    vi.mocked(getPlayer).mockResolvedValue({
      id: 'p1', name: 'Jogador Um', position: 'ATT', age: 27, overall: 78, pace: 60, finishing: 60,
      passing: 60, tackling: 60, goalkeeping: 10, stamina: 70, form: 0, morale: 70, yellowCards: 0,
      suspensionRemaining: 0, injuryMatches: 0, eligible: true, role: null, contractYears: 1,
      value: 0, wageDemand: 90000, fitness: 100, injurySeverity: 'NONE', attributes: [],
    } as never)
    vi.mocked(addToWatchlist).mockResolvedValue(undefined)

    renderPanel()
    fireEvent.click(await screen.findByText('Jogador Um'))

    const dialog = await screen.findByRole('dialog')
    expect(getPlayer).toHaveBeenCalledWith('sg-1', 'p1', 'test-token')

    const star = await within(dialog).findByRole('button', { name: 'Observar jogador' })
    fireEvent.click(star)

    await waitFor(() => expect(addToWatchlist).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('shows the counter offer and accepts it', async () => {
    vi.mocked(getPreContractEligible).mockResolvedValue([ELIGIBLE_UM])
    vi.mocked(makePreContractOffer).mockResolvedValue({ status: 'COUNTERED', demandedWage: 1100, agreement: null })
    vi.mocked(acceptPreContractCounter).mockResolvedValue({ status: 'AGREED', demandedWage: null, agreement: null })

    renderPanel()
    await screen.findByText('Jogador Um')

    fireEvent.click(screen.getByRole('button', { name: 'Propor' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Enviar proposta' }))

    await waitFor(() =>
      expect(makePreContractOffer).toHaveBeenCalledWith(
        'sg-1', { playerId: 'p1', wage: 90000, contractYears: 3 }, 'test-token',
      ),
    )

    expect(await within(dialog).findByText(/aceita por/i)).toBeInTheDocument()
    const acceptButton = within(dialog).getByRole('button', { name: 'Aceitar contraproposta' })

    fireEvent.click(acceptButton)

    await waitFor(() =>
      expect(acceptPreContractCounter).toHaveBeenCalledWith(
        'sg-1', 'p1', { contractYears: 3 }, 'test-token',
      ),
    )
  })

  it('renders a defend button for an incoming threat and renews at a 5% raise', async () => {
    vi.mocked(getPreContractAgreements).mockResolvedValue([THREAT_AGREEMENT])
    vi.mocked(renewContract).mockResolvedValue({
      playerId: 'p9', name: 'Ameaça Um', wage: 52500, contractYears: 3,
    })

    renderPanel()
    await screen.findByText(/Ameaça Um/)

    const defendButton = screen.getByRole('button', { name: /Renovar para defender/i })
    fireEvent.click(defendButton)

    const expectedWage = Math.ceil(THREAT_AGREEMENT.wage * 1.05 * 100) / 100
    await waitFor(() =>
      expect(renewContract).toHaveBeenCalledWith(
        'sg-1', 'p9', { wage: expectedWage, contractYears: 3 }, 'test-token',
      ),
    )
  })
})
