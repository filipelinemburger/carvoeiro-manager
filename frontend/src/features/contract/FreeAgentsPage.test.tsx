import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { FreeAgentsPage } from './FreeAgentsPage'
import * as api from '../../api/career'

vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ token: 'tok' }) }))

function renderPage() {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/free-agents?saveGameId=S1']}>
        <FreeAgentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FreeAgentsPage', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists free agents with their wage demand', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ currentRound: 0, totalRounds: 18 } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)

    renderPage()

    expect(await screen.findByText('Livre Um')).toBeInTheDocument()
  })

  it('pre-fills the wage field with the grouped demand and signs with a numeric wage', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      // A fractional demand to prove the default ceils above it (clears acceptsWage).
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 68466.4 },
    ] as never)
    const signSpy = vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)

    renderPage()

    const wageField = await screen.findByLabelText<HTMLInputElement>('Salário para Livre Um')
    // Field shows the demand grouped pt-BR style, ceiled to whole reais.
    expect(wageField.value).toBe('68.467')

    await userEvent.click(screen.getByRole('button', { name: 'Contratar' }))

    // The mutation must send a finite number (the old Number("90,000.00") path sent NaN -> null).
    expect(signSpy).toHaveBeenCalledWith(
      'S1', 'P1', { wage: 68467, contractYears: 3 }, 'tok',
    )
  })

  it('parses an edited grouped wage into a plain number on submit', async () => {
    vi.spyOn(api, 'getSaveGame').mockResolvedValue({ transferWindowOpen: true } as never)
    vi.spyOn(api, 'getFreeAgents').mockResolvedValue([
      { playerId: 'P1', name: 'Livre Um', position: 'ATT', age: 27, overall: 68, wageDemand: 100000 },
    ] as never)
    const signSpy = vi.spyOn(api, 'signFreeAgent').mockResolvedValue({} as never)

    renderPage()

    const wageField = await screen.findByLabelText<HTMLInputElement>('Salário para Livre Um')
    await userEvent.clear(wageField)
    await userEvent.type(wageField, '120000')
    expect(wageField.value).toBe('120.000')

    await userEvent.click(screen.getByRole('button', { name: 'Contratar' }))

    expect(signSpy).toHaveBeenCalledWith(
      'S1', 'P1', { wage: 120000, contractYears: 3 }, 'tok',
    )
  })
})
