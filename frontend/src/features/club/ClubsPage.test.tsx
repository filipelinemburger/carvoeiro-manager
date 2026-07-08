import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ClubsPage } from './ClubsPage'
import { AuthProvider } from '../../auth/AuthContext'
import type { ClubSummary } from '../../api/types'

vi.mock('../../api/career', () => ({
  getClubs: vi.fn(),
}))

import { getClubs } from '../../api/career'

const MANAGED_CLUB_ID = 'club-cri'

const clubs: ClubSummary[] = [
  { id: 'club-avi', name: 'Avaí FC', commonName: 'Avaí', shortName: 'AVA',
    primaryColor: '#0000aa', secondaryColor: '#ffffff', reputation: 80, squadSize: 25 },
  { id: MANAGED_CLUB_ID, name: 'Criciúma EC', commonName: 'Criciúma', shortName: 'CRI',
    primaryColor: '#f7d417', secondaryColor: '#111111', reputation: 74, squadSize: 22 },
  { id: 'club-flo', name: 'Figueirense FC', commonName: 'Figueirense', shortName: 'FIG',
    primaryColor: '#000000', secondaryColor: '#ffffff', reputation: 60, squadSize: 24 },
]

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={[`/clubs?saveGameId=sg-1&clubId=${MANAGED_CLUB_ID}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <Routes>
            <Route path="/clubs" element={<ClubsPage />} />
            <Route path="/club" element={<div>club-view-marker</div>} />
          </Routes>
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('ClubsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    vi.mocked(getClubs).mockResolvedValue(clubs)
  })

  it('lists every club in the order returned by the API (reputation desc)', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Avaí')).toBeInTheDocument())
    expect(screen.getByText('Criciúma')).toBeInTheDocument()
    expect(screen.getByText('Figueirense')).toBeInTheDocument()

    const rows = screen.getAllByRole('row')
    // Row 0 is the header; the API's reputation-desc order is preserved.
    expect(rows[1]).toHaveTextContent('Avaí')
    expect(rows[2]).toHaveTextContent('Criciúma')
    expect(rows[3]).toHaveTextContent('Figueirense')
    expect(getClubs).toHaveBeenCalledWith('sg-1', 'test-token')
  })

  it('highlights the managed club row', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Criciúma')).toBeInTheDocument())
    const rows = screen.getAllByRole('row')
    expect(rows[2]).toHaveAttribute('data-managed', 'true')
  })

  it('navigates to the club view when a row is clicked', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Avaí')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Avaí'))
    expect(await screen.findByText('club-view-marker')).toBeInTheDocument()
  })
})
