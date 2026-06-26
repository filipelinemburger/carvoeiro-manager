import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TacticsPanel } from './TacticsPanel'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getTactics: vi.fn(),
  setTactics: vi.fn(),
}))

import { getTactics, setTactics } from '../../api/career'

const mockGet = vi.mocked(getTactics)
const mockSet = vi.mocked(setTactics)

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <TacticsPanel
            saveGameId="sg-1"
            clubId="club-1"
            starters={[{ id: 'p1', name: 'Craque Um' }]}
          />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('TacticsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    mockGet.mockResolvedValue({
      mentality: 'ATTACKING',
      pressing: 'HIGH',
      marking: 'ZONAL',
      penaltyTakerId: null,
      freekickTakerId: null,
      cornerTakerId: null,
    })
    mockSet.mockResolvedValue({
      mentality: 'ATTACKING',
      pressing: 'HIGH',
      marking: 'MAN_TIGHT',
      penaltyTakerId: 'p1',
      freekickTakerId: null,
      cornerTakerId: null,
    })
  })

  it('prefills from getTactics and saves via setTactics', async () => {
    renderPanel()
    await waitFor(() => {
      expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING')
    })
    fireEvent.change(screen.getByLabelText('Marcação'), { target: { value: 'MAN_TIGHT' } })
    fireEvent.change(screen.getByLabelText('Pênaltis'), { target: { value: 'p1' } })
    fireEvent.click(screen.getByRole('button', { name: /salvar tática/i }))

    await waitFor(() => expect(mockSet).toHaveBeenCalled())
    expect(mockSet).toHaveBeenCalledWith(
      'sg-1',
      'club-1',
      expect.objectContaining({ marking: 'MAN_TIGHT', penaltyTakerId: 'p1' }),
      'test-token',
    )
  })
})
