import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { LoanInDialog } from './LoanInDialog'
import type { LoanOfferResult, MarketPlayer } from '../../api/types'

const PLAYER: MarketPlayer = {
  playerId: 'p1', name: 'Jogador Teste', position: 'MID', age: 25, overall: 70,
  clubId: 'club-9', clubName: 'Rival FC', value: 3000000, wage: 50000, wageDemand: 60000,
  listedReason: 'FINANCIAL', contractYears: 3, onLoan: false,
}

const REJECTED_KEY_PLAYER: LoanOfferResult = {
  offerId: 'o1', playerId: 'p1', playerName: 'Jogador Teste', status: 'REJECTED',
  managedClubIsOwner: false,
  ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: null, counterMayFaceOwner: null,
  refusal: 'KEY_PLAYER',
}

const REJECTED_TERMS: LoanOfferResult = {
  ...REJECTED_KEY_PLAYER, refusal: 'TERMS_REJECTED',
}

const COUNTERED: LoanOfferResult = {
  offerId: 'o1', playerId: 'p1', playerName: 'Jogador Teste', status: 'COUNTERED',
  managedClubIsOwner: false,
  ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 30, counterMayFaceOwner: false,
  refusal: 'TERMS_REJECTED',
}

describe('LoanInDialog', () => {
  it('renders a wage-share input and the "pode enfrentar o clube dono" switch, with no club picker', () => {
    render(<LoanInDialog player={PLAYER} pending={false} result={null}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByLabelText(/parcela salarial/i)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /pode enfrentar o clube dono/i })).toBeInTheDocument()
    // The owner is whoever holds the player — there's nothing to pick.
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('submitting calls onSubmit with exactly the wage share and switch state', () => {
    const onSubmit = vi.fn()
    render(<LoanInDialog player={PLAYER} pending={false} result={null}
      onSubmit={onSubmit} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/parcela salarial/i), { target: { value: '70' } })
    fireEvent.click(screen.getByRole('switch', { name: /pode enfrentar o clube dono/i }))
    fireEvent.click(screen.getByRole('button', { name: /pedir empréstimo/i }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith({ ownerWageShare: 70, mayFaceOwner: true })
  })

  it('shows the KEY_PLAYER refusal message and offers no way to improve the terms', () => {
    render(<LoanInDialog player={PLAYER} pending={false} result={REJECTED_KEY_PLAYER}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText('O clube não abre mão dele: é peça-chave do elenco.')).toBeInTheDocument()
    // No submit button, no wage-share field — nothing that invites a retry.
    expect(screen.queryByRole('button', { name: /pedir empréstimo/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/parcela salarial/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    // Not negotiable — the alert reads as final (error), not "try again" (warning).
    expect(screen.getByRole('alert')).toHaveClass('MuiAlert-colorError')
  })

  it('shows the TERMS_REJECTED refusal message and still offers no way to improve the terms in this dialog', () => {
    render(<LoanInDialog player={PLAYER} pending={false} result={REJECTED_TERMS}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText('Não gostaram dos termos — tente melhorar a proposta.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pedir empréstimo/i })).not.toBeInTheDocument()
    // isNegotiable(TERMS_REJECTED) is true — the alert's severity is the only signal that a
    // better offer could still work, so pin it explicitly.
    expect(screen.getByRole('alert')).toHaveClass('MuiAlert-colorWarning')
  })

  it('shows the owner\'s counter terms and wires "Aceitar contraproposta" to onAcceptCounter', () => {
    const onAcceptCounter = vi.fn()
    render(<LoanInDialog player={PLAYER} pending={false} result={COUNTERED}
      onSubmit={vi.fn()} onAcceptCounter={onAcceptCounter} onClose={vi.fn()} />)

    expect(screen.getByText(/30/)).toBeInTheDocument()
    expect(screen.getByText(/não/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /aceitar contraproposta/i }))
    expect(onAcceptCounter).toHaveBeenCalledTimes(1)
  })

  it('falls back to a generic refusal message and a "—" wage share when the backend sends null fields', () => {
    // The backend invariant (refusal set exactly when REJECTED, counterOwnerWageShare set exactly
    // when COUNTERED) holds today, but the types allow null — this must degrade gracefully
    // instead of rendering a blank Alert or the literal string "null%".
    const REJECTED_NO_REASON: LoanOfferResult = { ...REJECTED_KEY_PLAYER, refusal: null }
    const { unmount } = render(<LoanInDialog player={PLAYER} pending={false} result={REJECTED_NO_REASON}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText('Empréstimo recusado.')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveClass('MuiAlert-colorError')
    unmount()

    const COUNTERED_NO_SHARE: LoanOfferResult = { ...COUNTERED, counterOwnerWageShare: null }
    render(<LoanInDialog player={PLAYER} pending={false} result={COUNTERED_NO_SHARE}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText('—%')).toBeInTheDocument()
    expect(screen.queryByText(/null/)).not.toBeInTheDocument()
  })

  it('renders nothing when no player is selected', () => {
    const { container } = render(<LoanInDialog player={null} pending={false} result={null}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
