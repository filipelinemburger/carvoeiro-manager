import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NewCareerDialog } from './NewCareerDialog'
import { AuthProvider } from '../../auth/AuthContext'
import { createCareer } from '../../api/career'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('../../api/career', () => ({
  createCareer: vi.fn(),
}))

function renderDialog() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <NewCareerDialog open onClose={() => {}} />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NewCareerDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'asd', userId: 'U1' }))
  })

  it('disables the submit button until both fields are filled', () => {
    renderDialog()
    expect(screen.getByRole('button', { name: /iniciar carreira/i })).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    expect(screen.getByRole('button', { name: /iniciar carreira/i })).toBeDisabled()
  })

  it('submits the manager name and nationality and navigates to the new squad', async () => {
    ;(createCareer as Mock).mockResolvedValue({
      id: 'save-9', managedClubId: 'club-1', managerName: 'Filipe Silva', managerNationality: 'Brasil',
    })
    renderDialog()

    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    // Nationality: MUI Autocomplete — open it and pick 'Brasil' from the option list.
    fireEvent.mouseDown(screen.getByLabelText(/nacionalidade/i))
    fireEvent.click(await screen.findByText('Brasil'))

    fireEvent.click(screen.getByRole('button', { name: /iniciar carreira/i }))

    await waitFor(() => {
      expect(createCareer).toHaveBeenCalledWith(
        { managerName: 'Filipe Silva', managerNationality: 'Brasil' }, 'test-token',
      )
    })
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/squad?saveGameId=save-9&clubId=club-1')
    })
  })
})
