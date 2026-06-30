import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SeasonHistoryPage } from './SeasonHistoryPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getSeasonHistory: vi.fn(),
  getSeason: vi.fn(),
}))

import { getSeasonHistory, getSeason } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/history?saveGameId=sg-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <SeasonHistoryPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('SeasonHistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getSeasonHistory).mockResolvedValue([
      { seasonYear: 2026, competitionName: 'Campeonato Catarinense', championClubName: 'Campeao FC', managedClubPosition: 2, topScorerName: 'Artilheiro', topScorerGoals: 21 },
    ])
  })

  it('lists past seasons with champion and placing', async () => {
    // Given: history page is rendered with a past season
    renderPage()
    // When: data loads
    // Then: all required columns are present
    await waitFor(() => expect(screen.getByText('Campeao FC')).toBeInTheDocument())
    expect(screen.getByRole('cell', { name: '2026' })).toBeInTheDocument()
    expect(screen.getByText('Campeao FC')).toBeInTheDocument()
    expect(screen.getByText('2º')).toBeInTheDocument()
    expect(screen.getByText('Artilheiro (21)')).toBeInTheDocument()
  })

  it('loads the season detail when a season is selected', async () => {
    vi.mocked(getSeason).mockResolvedValue({
      seasonYear: 2026,
      competitionName: 'Campeonato Catarinense',
      championClubName: 'Campeao FC',
      table: [
        { position: 1, clubName: 'Campeao FC', played: 18, won: 14, drawn: 2, lost: 2, goalsFor: 40, goalsAgainst: 12, goalDifference: 28, points: 44 },
      ],
      topScorers: [{ playerName: 'Artilheiro', clubName: 'Campeao FC', goals: 21 }],
    })

    // Given: history page is rendered with a past season
    renderPage()
    // When: user clicks to view a season
    await waitFor(() => screen.getByText('Campeao FC'))
    fireEvent.click(screen.getByRole('button', { name: 'Ver 2026' }))

    // Then: the detail panel shows the top scorer in the format for detail list items
    await waitFor(() => expect(screen.getByText('Artilheiro (Campeao FC) — 21')).toBeInTheDocument())
    expect(getSeason).toHaveBeenCalledWith('sg-1', 2026, 'test-token')
  })
})
