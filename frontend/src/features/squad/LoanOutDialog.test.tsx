import { render, screen, fireEvent, within } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { LoanOutDialog } from './LoanOutDialog'
import type { PlayerResponse, ClubSummary, LoanOfferResult } from '../../api/types'

const PLAYER = {
  id: 'p1', name: 'Jogador Teste', position: 'MID', age: 25, overall: 70, contractYears: 3,
} as unknown as PlayerResponse

const CLUBS: ClubSummary[] = [
  { id: 'c1', name: 'Clube Um', commonName: null, shortName: 'CU1', primaryColor: null, secondaryColor: null, reputation: 60, squadSize: 24 },
  { id: 'c2', name: 'Clube Dois', commonName: null, shortName: 'CU2', primaryColor: null, secondaryColor: null, reputation: 55, squadSize: 22 },
]

const REJECTED_KEY_PLAYER: LoanOfferResult = {
  offerId: 'o1', playerId: 'p1', playerName: 'Jogador Teste', status: 'REJECTED',
  managedClubIsOwner: true,
  ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: null, counterMayFaceOwner: null,
  refusal: 'KEY_PLAYER',
}

const REJECTED_TERMS: LoanOfferResult = {
  ...REJECTED_KEY_PLAYER, refusal: 'TERMS_REJECTED',
}

const COUNTERED: LoanOfferResult = {
  offerId: 'o1', playerId: 'p1', playerName: 'Jogador Teste', status: 'COUNTERED',
  managedClubIsOwner: true,
  ownerWageShare: 50, mayFaceOwner: false, counterOwnerWageShare: 70, counterMayFaceOwner: true,
  refusal: 'TERMS_REJECTED',
}

describe('LoanOutDialog', () => {
  it('renders a club picker, a wage-share input and the "pode enfrentar meu clube" switch', () => {
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={null}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    const clubPicker = screen.getByRole('combobox', { name: /clube de destino/i })
    expect(within(clubPicker).getAllByRole('option').map(o => o.textContent))
      .toEqual(expect.arrayContaining(['Clube Um', 'Clube Dois']))
    expect(screen.getByLabelText(/parcela salarial/i)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /pode enfrentar meu clube/i })).toBeInTheDocument()
  })

  it('submitting calls onSubmit with exactly the chosen club, wage share and switch state', () => {
    const onSubmit = vi.fn()
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={null}
      onSubmit={onSubmit} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    fireEvent.change(screen.getByRole('combobox', { name: /clube de destino/i }), { target: { value: 'c2' } })
    fireEvent.change(screen.getByLabelText(/parcela salarial/i), { target: { value: '70' } })
    fireEvent.click(screen.getByRole('switch', { name: /pode enfrentar meu clube/i }))
    fireEvent.click(screen.getByRole('button', { name: /propor empréstimo/i }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith({ borrowerClubId: 'c2', ownerWageShare: 70, mayFaceOwner: true })
  })

  it('shows the KEY_PLAYER refusal message and offers no way to improve the terms', () => {
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={REJECTED_KEY_PLAYER}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText('O clube não abre mão dele: é peça-chave do elenco.')).toBeInTheDocument()
    // No submit button, no club picker, no wage-share field — nothing that invites a retry.
    expect(screen.queryByRole('button', { name: /propor empréstimo/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: /clube de destino/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/parcela salarial/i)).not.toBeInTheDocument()
  })

  it('shows the TERMS_REJECTED refusal message and still offers no way to improve the terms in this dialog', () => {
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={REJECTED_TERMS}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText('Não gostaram dos termos — tente melhorar a proposta.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /propor empréstimo/i })).not.toBeInTheDocument()
  })

  it('shows the AI\'s counter terms and wires "Aceitar contraproposta" to onAcceptCounter', () => {
    const onAcceptCounter = vi.fn()
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={COUNTERED}
      onSubmit={vi.fn()} onAcceptCounter={onAcceptCounter} onClose={vi.fn()} />)

    expect(screen.getByText(/70/)).toBeInTheDocument()
    expect(screen.getByText(/sim/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /aceitar contraproposta/i }))
    expect(onAcceptCounter).toHaveBeenCalledTimes(1)
  })

  it('falls back to a generic refusal message and a "—" wage share when the backend sends null fields', () => {
    // The backend invariant (refusal set exactly when REJECTED, counterOwnerWageShare set exactly
    // when COUNTERED) holds today, but the types allow null — this must degrade gracefully
    // instead of rendering a blank Alert or the literal string "null%".
    const REJECTED_NO_REASON: LoanOfferResult = { ...REJECTED_KEY_PLAYER, refusal: null }
    const { unmount } = render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={REJECTED_NO_REASON}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText('Empréstimo recusado.')).toBeInTheDocument()
    unmount()

    const COUNTERED_NO_SHARE: LoanOfferResult = { ...COUNTERED, counterOwnerWageShare: null }
    render(<LoanOutDialog player={PLAYER} clubs={CLUBS} pending={false} result={COUNTERED_NO_SHARE}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText('—%')).toBeInTheDocument()
    expect(screen.queryByText(/null/)).not.toBeInTheDocument()
  })

  it('renders nothing when no player is selected', () => {
    const { container } = render(<LoanOutDialog player={null} clubs={CLUBS} pending={false} result={null}
      onSubmit={vi.fn()} onAcceptCounter={vi.fn()} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
