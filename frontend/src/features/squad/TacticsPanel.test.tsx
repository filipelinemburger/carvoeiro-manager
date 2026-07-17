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
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
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

  it('displays friendly labels while keeping enum option values', async () => {
    renderPanel()

    const mentality = await screen.findByLabelText('Mentalidade') as HTMLSelectElement
    const mentalityOpts = Array.from(mentality.options).map(o => ({ value: o.value, label: o.text }))
    expect(mentalityOpts).toEqual([
      { value: 'DEFENSIVE', label: 'Defensiva' },
      { value: 'BALANCED', label: 'Equilibrada' },
      { value: 'ATTACKING', label: 'Ofensiva' },
    ])

    const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
    expect(Array.from(pressing.options).map(o => o.text)).toEqual(['Baixa', 'Média', 'Alta'])

    const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
    expect(Array.from(marking.options).map(o => o.text)).toEqual(['Por zona', 'Individual'])
  })

  it('invalidates its own tactics query after saving', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
    render(
      <MemoryRouter>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TacticsPanel saveGameId="sg-1" clubId="club-1" starters={[{ id: 'p1', name: 'Craque Um' }]} />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING'))
    fireEvent.click(screen.getByRole('button', { name: /salvar tática/i }))

    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['tactics', 'sg-1', 'club-1'] }),
    )
  })

  it('does not refetch tactics on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <TacticsPanel saveGameId="sg-1" clubId="club-1" starters={[{ id: 'p1', name: 'Craque Um' }]} />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING'))
    expect(mockGet).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByLabelText('Mentalidade')).toHaveValue('ATTACKING'))
    expect(mockGet).toHaveBeenCalledTimes(1)
  })
})
