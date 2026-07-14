import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '../../auth/AuthContext'
import { PlayerDetails } from './PlayerDetails'
import type { PlayerResponse } from '../../api/types'

vi.mock('../../api/playerStats', () => ({ getPlayerStats: vi.fn() }))
import { getPlayerStats } from '../../api/playerStats'

const PLAYER: PlayerResponse = {
  id: 'p1', name: 'Jogador Um', position: 'ATT', age: 24, overall: 70,
  pace: 70, finishing: 70, passing: 60, tackling: 40, goalkeeping: 10, stamina: 70,
  form: 0, morale: 70, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0,
  eligible: true, role: null, contractYears: 2, value: 1000000, wageDemand: 5000,
  fitness: 100, injurySeverity: 'NONE', attributes: [], transferListed: false, listedReason: null,
}

function renderDetails() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <AuthProvider>
      <QueryClientProvider client={qc}>
        <PlayerDetails player={PLAYER} onClose={() => {}} saveGameId="sg-1" />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

describe('PlayerDetails — Estatísticas tab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('does not fetch stats until the Estatísticas tab is opened', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [{ seasonYear: 2026, competitionName: 'Catarinense', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 }],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 },
    })
    renderDetails()
    expect(getPlayerStats).not.toHaveBeenCalled()

    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))
    await waitFor(() => expect(getPlayerStats).toHaveBeenCalledWith('sg-1', 'p1', 'test-token'))
  })

  it('renders a season row and the career totals row', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [{ seasonYear: 2026, competitionName: 'Catarinense', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 }],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 10, minutesPlayed: 900,
        goals: 3, assists: 2, fouls: 12, yellowCards: 1, redCards: 0 },
    })
    renderDetails()
    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))

    expect(await screen.findByText('Catarinense')).toBeInTheDocument()
    expect(screen.getByText('2026')).toBeInTheDocument()
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)   // goals appears in season + totals row
    expect(screen.getByText(/total/i)).toBeInTheDocument()
  })

  it('shows an empty-state message when the player has no recorded seasons', async () => {
    vi.mocked(getPlayerStats).mockResolvedValue({
      seasons: [],
      totals: { seasonYear: 0, competitionName: 'Total', matchesPlayed: 0, minutesPlayed: 0,
        goals: 0, assists: 0, fouls: 0, yellowCards: 0, redCards: 0 },
    })
    renderDetails()
    fireEvent.click(await screen.findByRole('tab', { name: /estatísticas/i }))
    expect(await screen.findByText(/nenhuma partida registrada/i)).toBeInTheDocument()
  })
})
