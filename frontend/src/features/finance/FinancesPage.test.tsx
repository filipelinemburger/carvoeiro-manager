import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FinancesPage } from './FinancesPage'
import { AuthProvider } from '../../auth/AuthContext'

vi.mock('../../api/career', () => ({ getFinances: vi.fn() }))
import { getFinances } from '../../api/career'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/finances?saveGameId=sg-1&clubId=club-1']}>
      <AuthProvider>
        <QueryClientProvider client={qc}>
          <FinancesPage />
        </QueryClientProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('FinancesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('elifoot_token', 'test-token')
    localStorage.setItem('elifoot_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders the balance and transactions', async () => {
    vi.mocked(getFinances).mockResolvedValue({
      balance: 12500000,
      transactions: [
        { seasonYear: 2026, round: 3, type: 'GATE', amount: 1935000, description: 'Renda de bilheteria' },
        { seasonYear: 2026, round: 3, type: 'WAGES', amount: -1352000, description: 'Salários' },
      ],
    })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(screen.getByText('Renda de bilheteria')).toBeInTheDocument()
    expect(screen.getByText('Bilheteria')).toBeInTheDocument() // GATE type label cell
  })

  it('shows a warning when the balance is negative', async () => {
    vi.mocked(getFinances).mockResolvedValue({ balance: -500000, transactions: [] })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent(/negativo/i)
  })
})
