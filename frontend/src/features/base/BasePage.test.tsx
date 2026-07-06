import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { BasePage } from './BasePage'
import { AuthProvider } from '../../auth/AuthContext'
import type { YouthProspectResponse } from '../../api/types'

vi.mock('../../api/youth', () => ({
  listProspects: vi.fn(),
  promoteProspect: vi.fn(),
  releaseProspect: vi.fn(),
}))

import { listProspects, promoteProspect, releaseProspect } from '../../api/youth'

const mockList = vi.mocked(listProspects)
const mockPromote = vi.mocked(promoteProspect)
const mockRelease = vi.mocked(releaseProspect)

const prospect = (over: Partial<YouthProspectResponse> = {}): YouthProspectResponse => ({
  id: 'p-1',
  name: 'Joia da Base',
  position: 'ATT',
  age: 16,
  overall: 58,
  pace: 60,
  finishing: 55,
  passing: 50,
  tackling: 30,
  goalkeeping: 10,
  stamina: 65,
  potentialStars: 4,
  ...over,
})

function renderWithProviders(saveGameId = 'sg-1', clubId = 'club-1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={[`/base?saveGameId=${saveGameId}&clubId=${clubId}`]}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <BasePage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('BasePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    mockList.mockResolvedValue([prospect()])
    mockPromote.mockResolvedValue(prospect())
    mockRelease.mockResolvedValue(undefined)
  })

  it('renders prospects with their potential stars', async () => {
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    expect(screen.getByLabelText('Potencial 4 de 5')).toBeInTheDocument()
  })

  it('shows the empty state when the academy is empty', async () => {
    mockList.mockResolvedValue([])
    renderWithProviders()
    await waitFor(() =>
      expect(screen.getByText(/Sua base ainda não tem jovens/)).toBeInTheDocument(),
    )
  })

  it('promotes a prospect after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Promover' }))
    await waitFor(() => expect(mockPromote).toHaveBeenCalledWith('sg-1', 'p-1', 'test-token'))
    confirmSpy.mockRestore()
  })

  it('does not release when confirmation is declined', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderWithProviders()
    await waitFor(() => expect(screen.getByText('Joia da Base')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Liberar' }))
    expect(mockRelease).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
