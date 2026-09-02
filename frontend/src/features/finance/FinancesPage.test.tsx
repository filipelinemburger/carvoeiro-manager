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
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
  })

  it('renders the balance and groups transactions by month', async () => {
    vi.mocked(getFinances).mockResolvedValue({
      balance: 12500000,
      transactions: [
        { seasonYear: 2026, round: 3, type: 'GATE', amount: 1935000, description: 'Renda de bilheteria', txDate: '2026-03-15' },
        { seasonYear: 2026, round: 3, type: 'MATCH_COST', amount: -76000, description: 'Custo da partida', txDate: '2026-03-15' },
        { seasonYear: 2026, round: 0, type: 'WAGES', amount: -6336000, description: 'Salários mensais', txDate: '2026-03-01' },
        { seasonYear: 2026, round: 0, type: 'SPONSOR', amount: 6000000, description: 'Patrocínio mensal', txDate: '2026-03-01' },
      ],
    })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    // Type labels are rendered
    expect(screen.getByText('Bilheteria')).toBeInTheDocument()
    // "Custo da partida" appears in both type column and description — use getAllByText
    expect(screen.getAllByText('Custo da partida').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('Salários')).toBeInTheDocument()
    expect(screen.getByText('Patrocínio')).toBeInTheDocument()
    // Descriptions
    expect(screen.getByText('Renda de bilheteria')).toBeInTheDocument()
    // The month heading for March 2026 should appear
    expect(screen.getByText(/março de 2026/i)).toBeInTheDocument()
  })

  it('labels the termination fee and the staff payroll in pt-BR', async () => {
    // Both enum values reach the ledger but had no label, so the raw enum string leaked into a
    // pt-BR-only UI.
    vi.mocked(getFinances).mockResolvedValue({
      balance: 12500000,
      transactions: [
        { seasonYear: 2026, round: 5, type: 'TERMINATION', amount: -2880000, description: 'Rescisão: Meio Um', txDate: '2026-04-10' },
        { seasonYear: 2026, round: 0, type: 'STAFF_WAGES', amount: -420000, description: 'Salários da comissão técnica', txDate: '2026-04-01' },
      ],
    })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())

    expect(screen.getByText('Rescisão de contrato')).toBeInTheDocument()
    expect(screen.getAllByText('Salários da comissão técnica').length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText('TERMINATION')).not.toBeInTheDocument()
    expect(screen.queryByText('STAFF_WAGES')).not.toBeInTheDocument()
  })

  it('groups rows into separate months', async () => {
    vi.mocked(getFinances).mockResolvedValue({
      balance: 10000000,
      transactions: [
        { seasonYear: 2026, round: 0, type: 'SPONSOR', amount: 6000000, description: 'Patrocínio mensal', txDate: '2026-01-15' },
        { seasonYear: 2026, round: 0, type: 'SPONSOR', amount: 6000000, description: 'Patrocínio mensal', txDate: '2026-02-01' },
      ],
    })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    // Two distinct month headings
    expect(screen.getByText(/janeiro de 2026/i)).toBeInTheDocument()
    expect(screen.getByText(/fevereiro de 2026/i)).toBeInTheDocument()
  })

  it('shows a warning when the balance is negative', async () => {
    vi.mocked(getFinances).mockResolvedValue({ balance: -500000, transactions: [] })
    renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent(/negativo/i)
  })

  it('shows empty state when there are no transactions', async () => {
    vi.mocked(getFinances).mockResolvedValue({ balance: 5000000, transactions: [] })
    renderPage()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(screen.getByText(/Nenhuma transação/i)).toBeInTheDocument()
  })

  it('does not refetch finances on a second mount when the query client is reused', async () => {
    vi.mocked(getFinances).mockResolvedValue({ balance: 5000000, transactions: [] })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const renderOnce = () => render(
      <MemoryRouter initialEntries={['/finances?saveGameId=sg-1&clubId=club-1']}>
        <AuthProvider>
          <QueryClientProvider client={qc}>
            <FinancesPage />
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
    const { unmount } = renderOnce()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(getFinances).toHaveBeenCalledTimes(1)
    unmount()

    renderOnce()
    await waitFor(() => expect(screen.getByText(/Saldo:/)).toBeInTheDocument())
    expect(getFinances).toHaveBeenCalledTimes(1)
  })
})
