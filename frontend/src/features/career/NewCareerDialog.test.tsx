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

function renderDialog(onClose: () => void = () => {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <NewCareerDialog open onClose={onClose} />
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
        { managerName: 'Filipe Silva', managerNationality: 'Brasil', sackingEnabled: true }, 'test-token',
      )
    })
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/squad?saveGameId=save-9&clubId=club-1')
    })
  })

  it('clears the fields on Cancel, so a still-mounted dialog never pre-fills stale text', () => {
    // NewCareerDialog is rendered unconditionally in Layout (only the MUI Dialog's own
    // visibility toggles), so its local state would otherwise survive across opens/closes.
    const onClose = vi.fn()
    renderDialog(onClose)

    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))

    expect(onClose).toHaveBeenCalled()
    expect(screen.getByLabelText(/nome do técnico/i)).toHaveValue('')
    expect(screen.getByRole('button', { name: /iniciar carreira/i })).toBeDisabled()
  })

  it('lets the manager opt out of being sacked', async () => {
    ;(createCareer as Mock).mockResolvedValue({ id: 'save-9', managedClubId: 'club-1' })
    renderDialog()

    // The board may sack by default
    const sacking = screen.getByLabelText('A diretoria pode me demitir')
    expect(sacking).toBeChecked()

    fireEvent.change(screen.getByLabelText(/nome do técnico/i), { target: { value: 'Filipe Silva' } })
    fireEvent.mouseDown(screen.getByLabelText(/nacionalidade/i))
    fireEvent.click(await screen.findByText('Brasil'))
    fireEvent.click(sacking)
    fireEvent.click(screen.getByRole('button', { name: /iniciar carreira/i }))

    await waitFor(() => {
      expect(createCareer).toHaveBeenCalledWith(
        { managerName: 'Filipe Silva', managerNationality: 'Brasil', sackingEnabled: false }, 'test-token',
      )
    })
  })
})
