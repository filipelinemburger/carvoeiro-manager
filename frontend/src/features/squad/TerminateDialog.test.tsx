import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { TerminateDialog } from './TerminateDialog'
import type { PlayerResponse } from '../../api/types'

const PLAYER = {
  id: 'p1', name: 'Jogador Teste', position: 'MID', age: 25, overall: 70,
  contractYears: 3, terminationFee: 5880000,
} as unknown as PlayerResponse

describe('TerminateDialog', () => {
  it('states the fee and the balance the club is left with', () => {
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)

    // The player's name legitimately appears twice (dialog title + warning alert), so a bare
    // getByText is ambiguous — same idiom SquadPage.test.tsx already uses for a repeated match.
    expect(screen.getAllByText(/Jogador Teste/).length).toBeGreaterThan(0)
    expect(screen.getByTestId('termination-fee')).toHaveTextContent('5.880.000')
    expect(screen.getByTestId('termination-new-balance')).toHaveTextContent('14.120.000')
  })

  it('warns that the player cannot be re-signed this season', () => {
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText(/não poderá ser recontratado/i)).toBeInTheDocument()
  })

  it('confirming calls onConfirm', () => {
    const onConfirm = vi.fn()
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={onConfirm} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /confirmar rescisão/i }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('cancelling calls onClose and never confirms', () => {
    const onConfirm = vi.fn()
    const onClose = vi.fn()
    render(<TerminateDialog player={PLAYER} balance={20000000} blockedReason={null}
      pending={false} onConfirm={onConfirm} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('blocks confirmation and shows why when the move is impossible', () => {
    render(<TerminateDialog player={PLAYER} balance={1000} blockedReason="Saldo insuficiente para a multa."
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: /confirmar rescisão/i })).toBeDisabled()
    expect(screen.getByText('Saldo insuficiente para a multa.')).toBeInTheDocument()
  })

  it('renders nothing when no player is selected', () => {
    const { container } = render(<TerminateDialog player={null} balance={20000000} blockedReason={null}
      pending={false} onConfirm={vi.fn()} onClose={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
