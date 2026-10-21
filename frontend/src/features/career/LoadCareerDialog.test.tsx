import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LoadCareerDialog } from './LoadCareerDialog'
import { AuthProvider } from '../../auth/AuthContext'
import { listCareers, deleteCareer } from '../../api/career'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('../../api/career', () => ({
  listCareers: vi.fn(),
  deleteCareer: vi.fn(),
}))

const USER_ID = 'U1'
const KEY = `carvoeiro_manager_save_game_id_${USER_ID}`

const CAREERS = [
  { id: 'save-1', managedClubName: 'Criciúma EC', seasonYear: 2026, currentRound: 5, currentDate: '2026-03-01', status: 'IN_PROGRESS', createdAt: '2026-01-15T10:00:00Z', lastPlayedAt: '2026-01-15T10:00:00Z' },
  { id: 'save-2', managedClubName: 'Outro Clube', seasonYear: 2026, currentRound: 1, currentDate: '2026-01-15', status: 'IN_PROGRESS', createdAt: '2026-01-10T10:00:00Z', lastPlayedAt: '2026-01-10T10:00:00Z' },
]

function renderDialog() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <LoadCareerDialog open onClose={() => {}} />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LoadCareerDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'asd', userId: USER_ID }))
  })
  afterEach(() => vi.restoreAllMocks())

  it('lists careers and marks the active one', async () => {
    localStorage.setItem(KEY, 'save-1')
    ;(listCareers as Mock).mockResolvedValue(CAREERS)

    renderDialog()

    expect(await screen.findByText('Criciúma EC')).toBeInTheDocument()
    expect(screen.getByText(/Temporada 2026 · Rodada 5/)).toBeInTheDocument()
    expect(screen.getByText('atual')).toBeInTheDocument()
  })

  it('resumes a career: sets the per-user save key and navigates to the dashboard', async () => {
    ;(listCareers as Mock).mockResolvedValue(CAREERS)

    renderDialog()

    const loadButtons = await screen.findAllByRole('button', { name: /^carregar$/i })
    fireEvent.click(loadButtons[0])

    expect(localStorage.getItem(KEY)).toBe('save-1')
    expect(mockNavigate).toHaveBeenCalledWith('/dashboard')
  })

  it('deletes the active career and clears the stored id', async () => {
    localStorage.setItem(KEY, 'save-1')
    ;(listCareers as Mock).mockResolvedValue(CAREERS)
    ;(deleteCareer as Mock).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    renderDialog()
    await screen.findByText('Criciúma EC')

    const deleteButtons = screen.getAllByRole('button', { name: /excluir carreira/i })
    fireEvent.click(deleteButtons[0])

    await waitFor(() => expect(deleteCareer).toHaveBeenCalledWith('save-1', 'test-token'))
    await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull())
  })

  it('shows an empty state when there are no careers', async () => {
    ;(listCareers as Mock).mockResolvedValue([])

    renderDialog()

    expect(await screen.findByText(/nenhuma carreira salva/i)).toBeInTheDocument()
  })

  it('marks a sacked career', async () => {
    ;(listCareers as Mock).mockResolvedValue([{ ...CAREERS[1], status: 'SACKED' }])

    renderDialog()

    expect(await screen.findByText('Demitido')).toBeInTheDocument()
  })
})
