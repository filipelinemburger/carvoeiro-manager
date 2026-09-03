import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SeasonSummaryPage } from './SeasonSummaryPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getStandings: vi.fn(),
  getTopScorers: vi.fn(),
  rolloverSeason: vi.fn(),
}))

import { getStandings, getTopScorers, rolloverSeason } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/season-summary?saveGameId=sg-1&competitionId=c-1&managedClubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <SeasonSummaryPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('SeasonSummaryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getStandings).mockResolvedValue([
      { clubId: 'club-9', clubName: 'Campeao FC', played: 18, won: 14, drawn: 2, lost: 2, goalsFor: 40, goalsAgainst: 12, goalDifference: 28, points: 44 },
      { clubId: 'club-1', clubName: 'Criciúma EC', played: 18, won: 12, drawn: 4, lost: 2, goalsFor: 35, goalsAgainst: 15, goalDifference: 20, points: 40 },
    ])
    vi.mocked(getTopScorers).mockResolvedValue([
      { playerName: 'Artilheiro', clubName: 'Campeao FC', goals: 21 },
    ])
  })

  it('shows the champion, table and top scorers', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText(/Campeão: Campeao FC/i)).toBeInTheDocument())
    expect(screen.getByText('Criciúma EC')).toBeInTheDocument()
    expect(screen.getAllByText(/Artilheiro/).length).toBeGreaterThan(0)
  })

  it('rolls over and shows squad changes', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [{ playerName: 'Veterano', position: 'DEF', age: 38 }],
      expiredContracts: [],
      youthIntake: [{ playerName: 'Jovem Silva', position: 'DEF', age: 17 }],
      youthDepartures: [],
      preContractArrivals: [],
      preContractDepartures: [],
      loanReturns: [],
      surplusListings: [],
      watchlistEvents: [],
    })

    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    await waitFor(() => expect(screen.getByText(/Aposentadorias/i)).toBeInTheDocument())
    expect(screen.getByText(/Veterano/)).toBeInTheDocument()
    expect(screen.getByText(/Jovem Silva/)).toBeInTheDocument()
    expect(screen.getByText(/Reforços da Base/i)).toBeInTheDocument()
    expect(rolloverSeason).toHaveBeenCalledWith('sg-1', 'test-token')
  })

  it('lists players who lapsed into free agency', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [],
      expiredContracts: [{ playerName: 'Zé Tigre', position: 'MID', age: 31 }],
      youthIntake: [],
      youthDepartures: [],
      preContractArrivals: [],
      preContractDepartures: [],
      loanReturns: [],
      surplusListings: [],
      watchlistEvents: [],
    })

    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    expect(await screen.findByText(/Contratos expirados/)).toBeInTheDocument()
    expect(screen.getByText(/Zé Tigre/)).toBeInTheDocument()
  })

  it('shows loan returns and surplus listings beside retirements and expired contracts', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027,
      currentRound: 0,
      retirements: [],
      expiredContracts: [],
      youthIntake: [],
      youthDepartures: [],
      preContractArrivals: [],
      preContractDepartures: [],
      loanReturns: [{ playerName: 'Emprestado Retornou', position: 'MID', age: 23 }],
      surplusListings: [{ playerName: 'Excedente do Elenco', position: 'DEF', age: 27 }],
      watchlistEvents: [],
    })

    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    expect(await screen.findByText(/Retornos de empréstimo/i)).toBeInTheDocument()
    expect(screen.getByText(/Emprestado Retornou/)).toBeInTheDocument()
    expect(screen.getByText(/Excedentes listados para transferência/i)).toBeInTheDocument()
    expect(screen.getByText(/Excedente do Elenco/)).toBeInTheDocument()
  })

  it('invalidates squad, youth, free agents, season history, clubs, and watchlist after rolling over', async () => {
    vi.mocked(rolloverSeason).mockResolvedValue({
      seasonYear: 2027, currentRound: 0, retirements: [], expiredContracts: [], youthIntake: [], youthDepartures: [],
      preContractArrivals: [], preContractDepartures: [], loanReturns: [], surplusListings: [], watchlistEvents: [],
    })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter initialEntries={['/season-summary?saveGameId=sg-1&competitionId=c-1&managedClubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <SeasonSummaryPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => screen.getByRole('button', { name: /próxima temporada/i }))
    fireEvent.click(screen.getByRole('button', { name: /próxima temporada/i }))

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['squad', 'sg-1', 'club-1'] }))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['youth', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['freeAgents', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['seasonHistory', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['clubs', 'sg-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['watchlist', 'sg-1'] })
  })
})
