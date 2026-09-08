import { render, screen, fireEvent, within } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { PendingLoanOffers } from './PendingLoanOffers'
import type { LoanOfferResult } from '../../api/types'

const COUNTERED: LoanOfferResult = {
  offerId: 'o1', playerId: 'p1', playerName: 'Jogador Teste', status: 'COUNTERED',
  ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 70, counterMayFaceOwner: true,
  refusal: 'TERMS_REJECTED',
}

const COUNTERED_2: LoanOfferResult = {
  ...COUNTERED, offerId: 'o2', playerId: 'p2', playerName: 'Outro Jogador',
  counterOwnerWageShare: 40, counterMayFaceOwner: false,
}

describe('PendingLoanOffers', () => {
  it('renders nothing when there are no open offers', () => {
    const { container } = render(
      <PendingLoanOffers offers={[]} windowOpen busy={false} onAccept={vi.fn()} onWithdraw={vi.fn()} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('lists every offer with its counter terms', () => {
    render(
      <PendingLoanOffers offers={[COUNTERED, COUNTERED_2]} windowOpen busy={false}
        onAccept={vi.fn()} onWithdraw={vi.fn()} />,
    )
    const row1 = within(screen.getByText('Jogador Teste').closest('li')!)
    expect(row1.getByText(/70% de salário/)).toBeInTheDocument()
    expect(row1.getByText(/^Contraproposta:.*pode enfrentar o dono$/)).toBeInTheDocument()

    const row2 = within(screen.getByText('Outro Jogador').closest('li')!)
    expect(row2.getByText(/40% de salário/)).toBeInTheDocument()
    expect(row2.getByText(/^Contraproposta:.*não pode enfrentar o dono$/)).toBeInTheDocument()
  })

  it('wires Aceitar and Desistir to their callbacks with the right offer id', () => {
    const onAccept = vi.fn()
    const onWithdraw = vi.fn()
    render(
      <PendingLoanOffers offers={[COUNTERED]} windowOpen busy={false}
        onAccept={onAccept} onWithdraw={onWithdraw} />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Aceitar' }))
    expect(onAccept).toHaveBeenCalledWith('o1')
    fireEvent.click(screen.getByRole('button', { name: 'Desistir' }))
    expect(onWithdraw).toHaveBeenCalledWith('o1')
  })

  it('disables Aceitar and explains why when the transfer window is closed, but Desistir stays enabled', () => {
    render(
      <PendingLoanOffers offers={[COUNTERED]} windowOpen={false} busy={false}
        onAccept={vi.fn()} onWithdraw={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'Aceitar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Desistir' })).toBeEnabled()
    expect(screen.getByText(/janela de transferências fechada/i)).toBeInTheDocument()
  })

  it('disables both actions while a mutation is in flight', () => {
    render(
      <PendingLoanOffers offers={[COUNTERED]} windowOpen busy
        onAccept={vi.fn()} onWithdraw={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'Aceitar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Desistir' })).toBeDisabled()
  })
})
