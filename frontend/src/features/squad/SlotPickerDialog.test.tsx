import { render, screen, fireEvent, within } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { SlotPickerDialog } from './SlotPickerDialog'
import { pitchSlots } from '../pitch/pitchSlots'
import type { PlayerResponse } from '../../api/types'

// The default 4-3-3's "le" slot (index 4) accepts only LATERAL_ESQUERDO — used across most
// tests below as a representative outfield slot.
const LE_SLOT = pitchSlots('4-3-3')[4]
const GK_SLOT = pitchSlots('4-3-3')[0]

function mkPlayer(overrides: Partial<PlayerResponse> & { id: string; name: string }): PlayerResponse {
  return {
    position: 'DEF', age: 24, overall: 70, detailedPosition: null, detailedPositionLabel: null,
    pace: 70, finishing: 60, passing: 60, tackling: 60, goalkeeping: 10, stamina: 70,
    form: 0, morale: 0, yellowCards: 0, suspensionRemaining: 0, injuryMatches: 0,
    eligible: true, role: null, contractYears: 2, value: 1000000, wageDemand: 5000,
    fitness: 100, injurySeverity: 'NONE', attributes: [],
    ...overrides,
  }
}

function renderDialog(props: Partial<React.ComponentProps<typeof SlotPickerDialog>> = {}) {
  const onPick = vi.fn()
  const onClose = vi.fn()
  const utils = render(
    <SlotPickerDialog
      open
      onClose={onClose}
      slot={LE_SLOT}
      players={[]}
      onPick={onPick}
      {...props}
    />,
  )
  return { ...utils, onPick, onClose }
}

describe('SlotPickerDialog', () => {
  it('shows the header naming the slot short label', () => {
    renderDialog()
    expect(screen.getByText('Quem joga aqui? (LE)')).toBeInTheDocument()
  })

  it('lists natural players before improvised ones, each group sorted by overall desc', () => {
    // Two naturals for LATERAL_ESQUERDO (higher overall first) and two improvised players —
    // one of whom out-rates BOTH naturals, to prove group membership beats raw overall.
    const n1 = mkPlayer({ id: 'n1', name: 'Natural Baixo', detailedPosition: 'LATERAL_ESQUERDO', overall: 70 })
    const n2 = mkPlayer({ id: 'n2', name: 'Natural Alto', detailedPosition: 'LATERAL_ESQUERDO', overall: 80 })
    const i1 = mkPlayer({ id: 'i1', name: 'Improvisado Alto', position: 'ATT', detailedPosition: 'ATACANTE', overall: 90 })
    const i2 = mkPlayer({ id: 'i2', name: 'Improvisado Baixo', position: 'MID', detailedPosition: 'MEIA_CENTRAL', overall: 60 })
    renderDialog({ players: [i1, n1, i2, n2] })

    const rows = screen.getAllByTestId(/^slot-picker-row-/)
    expect(rows.map(r => r.getAttribute('data-testid'))).toEqual([
      'slot-picker-row-n2', 'slot-picker-row-n1', 'slot-picker-row-i1', 'slot-picker-row-i2',
    ])
    expect(rows[0]).toHaveAttribute('data-natural', 'true')
    expect(rows[1]).toHaveAttribute('data-natural', 'true')
    expect(rows[2]).toHaveAttribute('data-natural', 'false')
    expect(rows[3]).toHaveAttribute('data-natural', 'false')
  })

  it('flags an improvised player with an "Improvisado" chip naming their real position(s), and not a natural one', () => {
    const natural = mkPlayer({ id: 'n1', name: 'Fulano Natural', detailedPosition: 'LATERAL_ESQUERDO' })
    const improvised = mkPlayer({
      id: 'i1', name: 'Fulano Improvisado', position: 'MID',
      detailedPosition: 'MEIA_ESQUERDA', altPositions: ['PONTA_ESQUERDA'],
    })
    renderDialog({ players: [natural, improvised] })

    const naturalRow = screen.getByTestId('slot-picker-row-n1')
    expect(within(naturalRow).queryByText('Improvisado')).not.toBeInTheDocument()

    const improvisedRow = screen.getByTestId('slot-picker-row-i1')
    expect(within(improvisedRow).getByText('Improvisado')).toBeInTheDocument()
    // Real positions (primary + alt), short-labeled and joined.
    expect(within(improvisedRow).getByText('ME · PE')).toBeInTheDocument()
    expect(within(improvisedRow).getByTitle(/Improvisado — posição natural: ME · PE/)).toBeInTheDocument()
  })

  it('excludes GK-position players from an outfield slot (the XI allows at most 1 goalkeeper)', () => {
    const keeper = mkPlayer({ id: 'gk1', name: 'Goleiro Um', position: 'GK', detailedPosition: 'GOLEIRO' })
    const defender = mkPlayer({ id: 'd1', name: 'Zagueiro Um', detailedPosition: 'LATERAL_ESQUERDO' })
    renderDialog({ slot: LE_SLOT, players: [keeper, defender] })

    expect(screen.queryByText('Goleiro Um')).not.toBeInTheDocument()
    expect(screen.getByText('Zagueiro Um')).toBeInTheDocument()
  })

  it('lists goalkeepers first for the GK slot, but still allows an outfield player (flagged)', () => {
    const keeper = mkPlayer({ id: 'gk1', name: 'Goleiro Um', position: 'GK', detailedPosition: 'GOLEIRO', overall: 60 })
    const outfield = mkPlayer({ id: 'd1', name: 'Zagueiro Um', overall: 90, detailedPosition: 'ZAGUEIRO' })
    renderDialog({ slot: GK_SLOT, players: [keeper, outfield] })

    const rows = screen.getAllByTestId(/^slot-picker-row-/)
    // The keeper (natural for GOLEIRO) leads despite the lower overall; the outfield player is
    // still offered, just flagged improvised.
    expect(rows.map(r => r.getAttribute('data-testid'))).toEqual(['slot-picker-row-gk1', 'slot-picker-row-d1'])
    expect(within(screen.getByTestId('slot-picker-row-d1')).getByText('Improvisado')).toBeInTheDocument()
  })

  it('highlights the current occupant', () => {
    const a = mkPlayer({ id: 'a', name: 'Jogador A', detailedPosition: 'LATERAL_ESQUERDO' })
    const b = mkPlayer({ id: 'b', name: 'Jogador B', detailedPosition: 'LATERAL_ESQUERDO' })
    renderDialog({ players: [a, b], currentOccupantId: 'a' })

    expect(within(screen.getByTestId('slot-picker-row-a')).getByText('Atual')).toBeInTheDocument()
    expect(within(screen.getByTestId('slot-picker-row-b')).queryByText('Atual')).not.toBeInTheDocument()
    expect(screen.getByTestId('slot-picker-row-a')).toHaveClass('Mui-selected')
  })

  it('reports the clicked player via onPick', () => {
    const a = mkPlayer({ id: 'a', name: 'Jogador A', detailedPosition: 'LATERAL_ESQUERDO' })
    const { onPick } = renderDialog({ players: [a] })

    fireEvent.click(screen.getByTestId('slot-picker-row-a'))
    expect(onPick).toHaveBeenCalledWith(a)
  })

  it('shows a placeholder when no candidate is available', () => {
    renderDialog({ players: [] })
    expect(screen.getByText('Nenhum jogador disponível.')).toBeInTheDocument()
  })

  it('fires onClose from the close button', () => {
    const { onClose } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: /fechar/i }))
    expect(onClose).toHaveBeenCalled()
  })
})
