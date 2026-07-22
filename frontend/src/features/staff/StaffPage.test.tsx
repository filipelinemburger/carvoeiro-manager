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
      { index: 0, name: 'Carlos Andrade', quality: 'REGULAR', wage: 20000, primaryAttribute: 10, secondaryAttribute: 9 },
      { index: 1, name: 'Marcos Vieira', quality: 'BOM', wage: 45000, primaryAttribute: 14, secondaryAttribute: 13 },
      { index: 2, name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000, primaryAttribute: 17, secondaryAttribute: 16 },
    ])
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('shows an empty slot for each unfilled role, then hires a browsed candidate', async () => {
    mockHireStaff.mockResolvedValue({
      assistente: null, preparadorFisico: null,
      olheiro: { name: 'Roberto Lima', quality: 'EXCELENTE', wage: 80000, primaryAttribute: 17, secondaryAttribute: 16 },
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

  it('does not refetch the roster or a role\'s candidates on a second mount when the query client is reused', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/staff?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <StaffPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))
    await waitFor(() => screen.getByText('Roberto Lima'))
    expect(mockGetStaffRoster).toHaveBeenCalledTimes(1)
    expect(mockGetStaffCandidates).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => screen.getByText('Olheiro'))
    fireEvent.click(within(screen.getByTestId('staff-role-OLHEIRO')).getByRole('button', { name: /contratar/i }))
    await waitFor(() => screen.getByText('Roberto Lima'))
    expect(mockGetStaffRoster).toHaveBeenCalledTimes(1)
    expect(mockGetStaffCandidates).toHaveBeenCalledTimes(1)
  })

  it('shows the two labeled attributes of a hired staff member', async () => {
    mockGetStaffRoster.mockResolvedValue({
      assistente: { name: 'Cléber Xavier', quality: 'EXCELENTE', wage: 80000, primaryAttribute: 17, secondaryAttribute: 16 },
      preparadorFisico: null,
      olheiro: null,
      fisioterapeuta: null,
    })
    renderPage()
    expect(await screen.findByText(/Conhecimento Tático: 17/)).toBeInTheDocument()
    expect(screen.getByText(/Avaliação de Jogadores: 16/)).toBeInTheDocument()
  })
})
