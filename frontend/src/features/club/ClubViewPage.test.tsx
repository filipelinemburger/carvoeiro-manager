import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ClubViewPage } from './ClubViewPage'
import { AuthProvider } from '../../auth/AuthContext'
import { ApiError } from '../../api/client'
import type { ClubSummary, PlayerResponse } from '../../api/types'

vi.mock('../../api/career', () => ({
  getSaveGame: vi.fn(),
  getClubs: vi.fn(),
  getSquad: vi.fn(),
  makeOffer: vi.fn(),
  getStaffRoster: vi.fn(),
}))

vi.mock('../../api/watchlist', () => ({
  getWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
}))

import { getSaveGame, getClubs, getSquad, makeOffer, getStaffRoster } from '../../api/career'
import { getWatchlist } from '../../api/watchlist'

const mockGetStaffRoster = vi.mocked(getStaffRoster)

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
    id: 'p1', name: 'Alvo Um', position: 'ATT', detailedPosition: null, detailedPositionLabel: null,
    age: 24, overall: 80, pace: 80, finishing: 78,
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
    vi.mocked(getWatchlist).mockResolvedValue([])
    mockGetStaffRoster.mockResolvedValue({
      tecnico: null, assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
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

  it('shows the stadium, city/state, and capacity line when all data is present', async () => {
    vi.mocked(getClubs).mockResolvedValue([
      clubs[0],
      { ...clubs[1], stadiumName: 'Estádio Heriberto Hülse', city: 'Criciúma', state: 'SC', stadiumCapacity: 19300 },
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Estádio Heriberto Hülse · Criciúma/SC · 19.300 lugares')).toBeInTheDocument()
  })

  it('omits the location line entirely when the club has no stadium data (null-safe)', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.queryByText(/lugares/)).not.toBeInTheDocument()
  })

  it('renders the location line without a dangling separator when city/state are missing', async () => {
    vi.mocked(getClubs).mockResolvedValue([
      clubs[0],
      { ...clubs[1], stadiumName: 'Estádio Heriberto Hülse', city: null, state: null, stadiumCapacity: 19300 },
    ])
    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(screen.getByText('Estádio Heriberto Hülse · 19.300 lugares')).toBeInTheDocument()
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
    expect(within(dialog).getByLabelText(/OVR 80/)).toBeInTheDocument()
  })

  it('shows a watch star for a rival player and lets the manager star them', async () => {
    const { addToWatchlist } = await import('../../api/watchlist')
    vi.mocked(addToWatchlist).mockResolvedValue(undefined)

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Alvo Um' }))
    const dialog = await screen.findByRole('dialog')

    const star = await within(dialog).findByRole('button', { name: 'Observar jogador' })
    fireEvent.click(star)

    await waitFor(() => expect(addToWatchlist).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('hides the watch star when viewing the managed club (own player)', async () => {
    vi.mocked(getSquad).mockResolvedValue({
      players: [player({ id: 'mp1', name: 'Meu Jogador' })], primaryColor: '#f7d417', secondaryColor: '#111111',
    })
    renderPage(MANAGED_CLUB_ID)
    await waitFor(() => expect(screen.getByText('Meu Jogador')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Meu Jogador' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByRole('button', { name: 'Observar jogador' })).not.toBeInTheDocument()
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

  it('shows the specific pt-BR reason when the offer is rejected with a known ApiError code', async () => {
    vi.mocked(makeOffer).mockRejectedValue(
      new ApiError('The transfer window is closed', 'TRANSFER_WINDOW_CLOSED', 422),
    )

    renderPage()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /fazer proposta/i }))

    expect(await screen.findByText('Janela de transferências fechada.')).toBeInTheDocument()
    expect(screen.queryByText('Erro ao enviar proposta.')).not.toBeInTheDocument()
  })

  it('hides "Fazer proposta" when viewing the managed club', async () => {
    vi.mocked(getSquad).mockResolvedValue({
      players: [player({ id: 'mp1', name: 'Meu Jogador' })], primaryColor: '#f7d417', secondaryColor: '#111111',
    })
    renderPage(MANAGED_CLUB_ID)
    await waitFor(() => expect(screen.getByText('Meu Jogador')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /fazer proposta/i })).not.toBeInTheDocument()
  })

  it('invalidates the managed squad when an offer for a rival player is accepted', async () => {
    vi.mocked(makeOffer).mockResolvedValue({
      id: 'o1', playerId: 'p1', playerName: 'Alvo Um', fromClubId: MANAGED_CLUB_ID, fromClubName: 'Criciúma',
      toClubId: OTHER_CLUB_ID, toClubName: 'Rival FC', direction: 'OUTGOING', amount: 2688000,
      counterAmount: null, status: 'ACCEPTED', round: 0, wage: 60000, contractYears: 3,
    })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter initialEntries={[`/club?saveGameId=sg-1&clubId=${OTHER_CLUB_ID}`]}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <ClubViewPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /fazer proposta/i }))

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /fazer proposta/i }))

    await waitFor(() => expect(screen.getByText(/Proposta aceita/i)).toBeInTheDocument())
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', MANAGED_CLUB_ID] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['finances', 'sg-1', MANAGED_CLUB_ID] })
  })

  it('does not refetch clubs on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={[`/club?saveGameId=sg-1&clubId=${OTHER_CLUB_ID}`]}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <ClubViewPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(getClubs).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText('Alvo Um')).toBeInTheDocument())
    expect(getClubs).toHaveBeenCalledTimes(1)
  })

  it("shows the club's coaching staff", async () => {
    mockGetStaffRoster.mockResolvedValue({
      tecnico: { name: 'Cléber Xavier', quality: 'BOM', wage: 45000, primaryAttribute: 14, secondaryAttribute: 13 },
      assistente: null,
      preparadorFisico: { name: 'Léo Cupertino', quality: 'EXCELENTE', wage: 80000, primaryAttribute: 17, secondaryAttribute: 16 },
      olheiro: null,
      fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Comissão Técnica'))
    expect(screen.getByText('Cléber Xavier')).toBeInTheDocument()
    expect(screen.getByText('Léo Cupertino')).toBeInTheDocument()
    // An unresearched slot is omitted, not rendered as an empty row.
    expect(screen.queryByText('Olheiro')).not.toBeInTheDocument()
  })

  it('shows quality, wage and attributes for a rival club employee', async () => {
    mockGetStaffRoster.mockResolvedValue({
      tecnico: { name: 'Cléber Xavier', quality: 'BOM', wage: 45000, primaryAttribute: 14, secondaryAttribute: 13 },
      assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Comissão Técnica'))
    expect(screen.getByText('Bom')).toBeInTheDocument()
    expect(screen.getByText(/45\.000/)).toBeInTheDocument()
    expect(screen.getByText('Trabalho Tático: 14')).toBeInTheDocument()
    expect(screen.getByText('Motivação: 13')).toBeInTheDocument()
  })

  it('renders the managed club head-coach slot as a bare name (the player holds that job)', async () => {
    vi.mocked(getSquad).mockResolvedValue({
      players: [player({ id: 'mp1', name: 'Meu Jogador' })], primaryColor: '#f7d417', secondaryColor: '#111111',
    })
    // The managed club's own head coach is the player: no quality, no wage, attributes are 0 placeholders.
    mockGetStaffRoster.mockResolvedValue({
      tecnico: { name: 'Você', quality: null, wage: null, primaryAttribute: 0, secondaryAttribute: 0 },
      assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
    renderPage(MANAGED_CLUB_ID)

    await waitFor(() => screen.getByText('Comissão Técnica'))
    expect(screen.getByText('Você')).toBeInTheDocument()
    expect(screen.queryByText(/\/mês/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Trabalho Tático/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Motivação/)).not.toBeInTheDocument()
  })
})
