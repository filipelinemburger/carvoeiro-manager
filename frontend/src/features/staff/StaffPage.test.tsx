import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StaffPage } from './StaffPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({
  getStaffRoster: vi.fn(),
  getStaffCandidates: vi.fn(),
  hireStaff: vi.fn(),
}))

import { getStaffRoster, getStaffCandidates, hireStaff } from '../../api/career'

const mockGetStaffRoster = vi.mocked(getStaffRoster)
const mockGetStaffCandidates = vi.mocked(getStaffCandidates)
const mockHireStaff = vi.mocked(hireStaff)

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/staff?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <StaffPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('StaffPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStaffRoster.mockResolvedValue({
      assistente: null, preparadorFisico: null, olheiro: null, fisioterapeuta: null,
    })
    mockGetStaffCandidates.mockResolvedValue([
      { index: 0, name: 'Carlos Andrade', quality: 'REGULAR', wage: 20000 },
      { index: 1, name: 'Marcos Vieira', quality: 'BOM', wage: 45000 },
      { index: 2, name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000 },
    ])
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows an empty slot for each unfilled role, then hires a browsed candidate', async () => {
    mockHireStaff.mockResolvedValue({
      assistente: null, preparadorFisico: null,
      olheiro: { name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000 },
      fisioterapeuta: null,
    })
    renderPage()

    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))

    await waitFor(() => screen.getByText('Roberto Lima'))
    fireEvent.click(screen.getByRole('button', { name: /roberto lima/i }))

    await waitFor(() =>
      expect(mockHireStaff).toHaveBeenCalledWith('sg-1', 'club-1', 'OLHEIRO', 2, 'test-token'),
    )
  })

  it('shows an error message when the candidate list fails to load', async () => {
    mockGetStaffCandidates.mockRejectedValue(new Error('fail'))
    renderPage()

    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))

    expect(await screen.findByText('Erro ao carregar candidatos.')).toBeInTheDocument()
  })
})
