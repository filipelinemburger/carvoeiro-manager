import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LiveMatchPage } from './LiveMatchPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/liveMatch', () => ({
  getLiveMatch: vi.fn(),
  advanceLiveMatch: vi.fn(),
  finishLiveMatch: vi.fn(),
  substituteLiveMatch: vi.fn(),
}))
import { getLiveMatch, advanceLiveMatch, finishLiveMatch, substituteLiveMatch } from '../../api/liveMatch'

const STATE = {
  fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
  homeClubName: 'Criciúma', awayClubName: 'Rival', managedIsHome: true,
  minute: 12, homeGoals: 1, awayGoals: 0, status: 'IN_PROGRESS',
  events: [{ minute: 12, type: 'GOAL', home: true, playerId: 'p1', playerName: 'Camisa 9',
    playerInId: null, playerInName: null, severity: null, text: 'GOL! Camisa 9 balança as redes!' }],
  onPitch: [], bench: [], subsRemaining: 5,
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/match?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}><LiveMatchPage /></QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('LiveMatchPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(advanceLiveMatch).mockResolvedValue({ ...STATE, minute: 13, status: 'IN_PROGRESS' } as never)
  })

  it('shows the score and the commentary feed', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue(STATE as never)
    renderPage()
    expect(await screen.findByText(/GOL! Camisa 9/)).toBeInTheDocument()
    expect(screen.getByText(/Criciúma/)).toBeInTheDocument()
  })

  it('at full time, Ver resumo finishes and navigates to the news', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({ ...STATE, minute: 90, status: 'FULL_TIME' } as never)
    vi.mocked(finishLiveMatch).mockResolvedValue({ type: 'MATCH' } as never)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /ver resumo/i }))
    await waitFor(() => expect(finishLiveMatch).toHaveBeenCalledWith('sg-1', 'test-token'))
  })

  it('opens the sub dialog and substitutes a bench player', async () => {
    vi.mocked(getLiveMatch).mockResolvedValue({
      ...STATE, status: 'PAUSED_INJURY',
      onPitch: [{ playerId: 'p1', name: 'Titular', position: 'MID', overall: 75, fitness: 40, hobbled: true, onPitch: true, used: false, eligible: true }],
      bench: [{ playerId: 'b1', name: 'Reserva', position: 'MID', overall: 70, fitness: 100, hobbled: false, onPitch: false, used: false, eligible: true }],
      subsRemaining: 5,
    } as never)
    vi.mocked(substituteLiveMatch).mockResolvedValue({ ...STATE, subsRemaining: 4 } as never)

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: /substituir/i }))
    fireEvent.click(await screen.findByText(/Titular/))
    fireEvent.click(screen.getByText('Reserva'))
    fireEvent.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() =>
      expect(substituteLiveMatch).toHaveBeenCalledWith('sg-1', { outPlayerId: 'p1', inPlayerId: 'b1' }, 'test-token'))
  })
})
