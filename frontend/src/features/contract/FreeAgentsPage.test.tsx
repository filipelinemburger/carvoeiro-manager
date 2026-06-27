import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FreeAgentsPage } from './FreeAgentsPage'
import * as api from '../../api/career'

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'tok' }) }))

function renderPage() {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
        <FreeAgentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FreeAgentsPage', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists free agents with their wage demand', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ currentRound: 0, totalRounds: 18 } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)

    renderPage()

    expect(await screen.findByText('Livre Um')).toBeInTheDocument()
  })
})
