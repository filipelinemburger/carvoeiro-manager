import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Pitch } from './Pitch'
import type { TeamOnPitch, PlacedPlayer } from './Pitch'

function xi(): TeamOnPitch {
  const mk = (i: number, position: 'GK' | 'DEF' | 'MID' | 'ATT') =>
    ({ id: `p${i}`, surname: `Jog${i}`, position, fitness: 80 })
  return {
    formation: '4-4-2',
    kit: { primary: '#f7d417', secondary: '#111111' },
    players: [
      mk(1, 'GK'),
      mk(2, 'DEF'), mk(3, 'DEF'), mk(4, 'DEF'), mk(5, 'DEF'),
      mk(6, 'MID'), mk(7, 'MID'), mk(8, 'MID'), mk(9, 'MID'),
      mk(10, 'ATT'), mk(11, 'ATT'),
    ],
    showNames: true,
  }
}

describe('Pitch', () => {
  it('renders the pitch and eleven home tokens', () => {
    render(<Pitch home={xi()} />)
    expect(screen.getByTestId('pitch')).toBeInTheDocument()
    expect(screen.getAllByTestId('player-token')).toHaveLength(11)
  })

  it('renders 22 tokens when an away team is supplied', () => {
    const away: TeamOnPitch = { ...xi(), kit: { primary: '#003399', secondary: '#ffffff' }, showNames: false }
    render(<Pitch home={xi()} away={away} />)
    expect(screen.getAllByTestId('player-token')).toHaveLength(22)
  })

  it('shows home surnames', () => {
    render(<Pitch home={xi()} />)
    expect(screen.getByText('Jog1')).toBeInTheDocument()
    expect(screen.getByText('Jog11')).toBeInTheDocument()
  })

  it('always renders all 11 slots, keeping empty slots as nameless placeholders', () => {
    const mk = (i: number, position: 'GK' | 'DEF' | 'MID' | 'ATT') =>
      ({ id: `p${i}`, surname: `Jog${i}`, position, fitness: 80 })
    // A 4-4-2 with no goalkeeper and only 3 defenders selected (9 players).
    const partial: TeamOnPitch = {
      formation: '4-4-2',
      kit: { primary: '#f7d417', secondary: '#111111' },
      players: [
        mk(2, 'DEF'), mk(3, 'DEF'), mk(4, 'DEF'),
        mk(6, 'MID'), mk(7, 'MID'), mk(8, 'MID'), mk(9, 'MID'),
        mk(10, 'ATT'), mk(11, 'ATT'),
      ],
      showNames: true,
    }
    render(<Pitch home={partial} />)
    // All 11 formation slots stay on the pitch...
    expect(screen.getAllByTestId('player-token')).toHaveLength(11)
    // ...with the 2 unfilled slots (GK + one DEF) shown as nameless placeholders.
    expect(screen.getAllByTestId('player-token').filter(t => t.getAttribute('data-empty') === 'true')).toHaveLength(2)
    // an empty slot carries no surname
    expect(screen.queryByText('Jog5')).not.toBeInTheDocument()
  })

  it('renders an empty formation shape (11 placeholders) when no players are selected', () => {
    const empty: TeamOnPitch = {
      formation: '4-3-3',
      kit: { primary: '#003399', secondary: '#ffffff' },
      players: [],
      showNames: true,
    }
    render(<Pitch home={empty} />)
    const tokens = screen.getAllByTestId('player-token')
    expect(tokens).toHaveLength(11)
    expect(tokens.every(t => t.getAttribute('data-empty') === 'true')).toBe(true)
  })
})

describe('explicit slot placement', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }
  const placed = (id: string, surname: string, position: 'GK' | 'DEF' | 'MID' | 'ATT',
                  slotIndex: number): PlacedPlayer =>
    ({ id, surname, position, detailedPosition: null, fitness: 100, slotIndex })

  it('places each player at his explicit slot, not by list order', () => {
    // Two ATT players given in one order but slotIndexes in the other: explicit wins.
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [
        placed('gk', 'Golem', 'GK', 0),
        placed('a-right', 'Direita', 'ATT', 10),
        placed('a-left', 'Esquerda', 'ATT', 8),
      ],
    }
    const { container } = render(<Pitch home={home} />)
    expect(container.querySelector('[data-slot-index="8"]')).toHaveTextContent('Esquerda')
    expect(container.querySelector('[data-slot-index="10"]')).toHaveTextContent('Direita')
    expect(container.querySelector('[data-slot-index="0"]')).toHaveTextContent('Golem')
  })

  it('renders unassigned slots as empty placeholders', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [placed('gk', 'Golem', 'GK', 0)],
    }
    const { container } = render(<Pitch home={home} />)
    // 11 slots total: 1 occupied + 10 placeholders.
    expect(container.querySelectorAll('[data-slot-index]')).toHaveLength(11)
    expect(container.querySelectorAll('[data-testid="player-token"][data-empty="true"]')).toHaveLength(10)
  })

  it('falls back to line-zip placement when any player lacks a slotIndex', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [
        { id: 'gk', surname: 'Golem', position: 'GK', detailedPosition: null, fitness: 100 },
        { id: 'd1', surname: 'Zaga', position: 'DEF', detailedPosition: null, fitness: 100 },
      ],
    }
    const { container } = render(<Pitch home={home} />)
    // Legacy zip: the first DEF fills the first DEF slot (index 1 in 4-3-3).
    expect(container.querySelector('[data-slot-index="1"]')).toHaveTextContent('Zaga')
  })
})
