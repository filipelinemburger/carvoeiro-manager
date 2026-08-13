import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
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

describe('out-of-position tint uses natural-vs-improvised (primary + alt positions)', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  it('does not warn when only the player\'s ALTERNATE position matches the slot', () => {
    // Slot 1 (4-3-3) accepts LATERAL_DIREITO only. This player's primary is ZAGUEIRO (not
    // accepted) but an alt position IS accepted — the picker would call this natural, so the
    // pitch tint must agree.
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [{
        id: 'p1', surname: 'Alt', position: 'DEF', fitness: 100, slotIndex: 1,
        detailedPosition: 'ZAGUEIRO', altPositions: ['LATERAL_DIREITO'],
      }],
    }
    const { container } = render(<Pitch home={home} />)
    const token = container.querySelector('[data-slot-index="1"] [data-testid="player-token"]')!
    expect(token).not.toHaveAttribute('data-out-of-position', 'true')
  })

  it('still warns when neither the primary nor any alternate position matches the slot', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [{
        id: 'p1', surname: 'Fora', position: 'DEF', fitness: 100, slotIndex: 1,
        detailedPosition: 'ZAGUEIRO', altPositions: ['MEIA_CENTRAL'],
      }],
    }
    const { container } = render(<Pitch home={home} />)
    const token = container.querySelector('[data-slot-index="1"] [data-testid="player-token"]')!
    expect(token).toHaveAttribute('data-out-of-position', 'true')
  })

  it('still warns exactly as before for a player with no alternate positions at all', () => {
    const home: TeamOnPitch = {
      formation: '4-3-3', kit, showNames: true,
      players: [{
        id: 'p1', surname: 'Fora', position: 'DEF', fitness: 100, slotIndex: 1,
        detailedPosition: 'ZAGUEIRO',
      }],
    }
    const { container } = render(<Pitch home={home} />)
    const token = container.querySelector('[data-slot-index="1"] [data-testid="player-token"]')!
    expect(token).toHaveAttribute('data-out-of-position', 'true')
  })
})

describe('line-zip overflow (assign fallback)', () => {
  it('places an overflow player (more players on a line than the formation has slots for) into a remaining empty slot instead of dropping them', () => {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT'): PlacedPlayer =>
      ({ id, surname: id.toUpperCase(), position, detailedPosition: null, fitness: 100 })
    // 4-2-4 genuinely has only 2 MID slots (a double pivot); fielding 3 MID players overflows
    // that line. None of these players carry a slotIndex, so `assign`'s legacy line-zip runs.
    const home: TeamOnPitch = {
      formation: '4-2-4',
      kit: { primary: '#f7d417', secondary: '#111111' },
      showNames: true,
      players: [
        mk('gk', 'GK'),
        mk('d1', 'DEF'), mk('d2', 'DEF'), mk('d3', 'DEF'), mk('d4', 'DEF'),
        mk('m1', 'MID'), mk('m2', 'MID'), mk('m3', 'MID'),
        mk('a1', 'ATT'), mk('a2', 'ATT'), mk('a3', 'ATT'),
      ],
    }
    render(<Pitch home={home} />)
    const tokens = screen.getAllByTestId('player-token')
    expect(tokens).toHaveLength(11)
    // No player silently vanishes: every player's surname is rendered somewhere on the pitch.
    for (const p of home.players) {
      expect(screen.getByText(p.surname)).toBeInTheDocument()
    }
    // With exactly 11 players for 11 slots, nothing should be left as an empty placeholder.
    expect(tokens.filter(t => t.getAttribute('data-empty') === 'true')).toHaveLength(0)
  })
})

describe('drag and drop', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  function fullXi(): PlacedPlayer[] {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT', slotIndex: number): PlacedPlayer =>
      ({ id, surname: id, position, detailedPosition: null, fitness: 100, slotIndex })
    return [
      mk('gk', 'GK', 0),
      mk('d1', 'DEF', 1), mk('d2', 'DEF', 2), mk('d3', 'DEF', 3), mk('d4', 'DEF', 4),
      mk('m1', 'MID', 5), mk('m2', 'MID', 6), mk('m3', 'MID', 7),
      mk('a1', 'ATT', 8), mk('a2', 'ATT', 9), mk('a3', 'ATT', 10),
    ]
  }

  function renderDraggable(players = fullXi()) {
    const onSwap = vi.fn()
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players }} onSwap={onSwap} />,
    )
    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const token = (slot: number) => container.querySelector(`[data-slot-index="${slot}"]`)!
    return { onSwap, container, token }
  }

  it('dropping a token on another slot reports both slot indexes', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
  })

  it('dropping onto an unoccupied slot also reports it (move)', () => {
    const players = fullXi().filter(p => p.slotIndex !== 9) // striker slot empty
    const { onSwap, token } = renderDraggable(players)
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
  })

  it('a drop far from any slot snaps back (no callback)', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 399, clientY: 10 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('the GK token is not draggable', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(0), { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(token(0), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('the GK slot is never a drop target', () => {
    const { onSwap, token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 564 })
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('highlights the drop candidate while dragging over it', () => {
    const { token } = renderDraggable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(token(9)).toHaveAttribute('data-drop-candidate', 'true')
  })

  it('without onSwap the pitch stays inert', () => {
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players: fullXi() }} />,
    )
    const token = container.querySelector('[data-slot-index="1"]')!
    fireEvent.pointerDown(token, { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token, { pointerId: 1, clientX: 200, clientY: 192 })
    // No crash, no drag state — nothing to assert beyond absence of the candidate attr.
    expect(container.querySelector('[data-drop-candidate="true"]')).toBeNull()
  })
})

describe('tap vs drag (onSlotClick)', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  function fullXi(): PlacedPlayer[] {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT', slotIndex: number): PlacedPlayer =>
      ({ id, surname: id, position, detailedPosition: null, fitness: 100, slotIndex })
    return [
      mk('gk', 'GK', 0),
      mk('d1', 'DEF', 1), mk('d2', 'DEF', 2), mk('d3', 'DEF', 3), mk('d4', 'DEF', 4),
      mk('m1', 'MID', 5), mk('m2', 'MID', 6), mk('m3', 'MID', 7),
      mk('a1', 'ATT', 8), mk('a2', 'ATT', 9), mk('a3', 'ATT', 10),
    ]
  }

  function renderTappable(players = fullXi(), onSwap = vi.fn()) {
    const onSlotClick = vi.fn()
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players }} onSwap={onSwap} onSlotClick={onSlotClick} />,
    )
    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const token = (slot: number) => container.querySelector(`[data-slot-index="${slot}"]`)!
    return { onSwap, onSlotClick, container, token }
  }

  it('a tap (down/up with no meaningful movement) on an outfield slot fires onSlotClick, not onSwap', () => {
    const { onSwap, onSlotClick, token } = renderTappable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    expect(onSlotClick).toHaveBeenCalledWith(1)
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('a tap on the GK slot fires onSlotClick even though the GK token is never draggable', () => {
    const { onSwap, onSlotClick, token } = renderTappable()
    fireEvent.pointerDown(token(0), { pointerId: 1, clientX: 200, clientY: 564 })
    fireEvent.pointerUp(token(0), { pointerId: 1, clientX: 200, clientY: 564 })
    expect(onSlotClick).toHaveBeenCalledWith(0)
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('a tap on an empty slot fires onSlotClick', () => {
    const players = fullXi().filter(p => p.id !== 'a3') // striker slot (10) left empty
    const { onSwap, onSlotClick, token } = renderTappable(players)
    fireEvent.pointerDown(token(10), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(token(10), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSlotClick).toHaveBeenCalledWith(10)
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('a genuine drag (movement past the click threshold) still fires onSwap, not onSlotClick', () => {
    const { onSwap, onSlotClick, token } = renderTappable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
    expect(onSlotClick).not.toHaveBeenCalled()
  })

  it('dragging still works exactly as before when onSlotClick is also supplied', () => {
    const { onSwap, token } = renderTappable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(token(9)).toHaveAttribute('data-drop-candidate', 'true')
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    expect(onSwap).toHaveBeenCalledWith(1, 9)
  })

  it('a drag that travels away then returns near the origin still resolves as a drag, not a tap (tracks total distance travelled, not net displacement)', () => {
    const { onSwap, onSlotClick, token } = renderTappable()
    fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    // Move well past the click threshold...
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
    // ...then bring it back to (approximately) where it started before releasing. Net
    // displacement is now ~0, but the interaction genuinely was a drag.
    fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
    expect(onSlotClick).not.toHaveBeenCalled()
    // The release point coincides with the origin slot, which is never its own drop candidate,
    // so it snaps back silently rather than firing onSwap.
    expect(onSwap).not.toHaveBeenCalled()
  })

  it('onSlotClick is never fired by tapping a mirrored away-team token', () => {
    const onSlotClick = vi.fn()
    const away = { formation: '4-3-3', kit: { primary: '#003399', secondary: '#fff' }, showNames: true, players: fullXi() }
    const { container } = render(
      <Pitch home={{ formation: '4-3-3', kit, showNames: true, players: fullXi() }} away={away} onSlotClick={onSlotClick} />,
    )
    // Home renders first, away second — both share `data-slot-index`, so the second match for
    // slot 1 is the away (mirrored) token.
    const awayToken = container.querySelectorAll('[data-slot-index="1"]')[1]
    fireEvent.pointerDown(awayToken, { pointerId: 1, clientX: 64, clientY: 474 })
    fireEvent.pointerUp(awayToken, { pointerId: 1, clientX: 64, clientY: 474 })
    expect(onSlotClick).not.toHaveBeenCalled()
  })
})

describe('context menu (right-click and long-press)', () => {
  const kit = { primary: '#f7d417', secondary: '#111111' }

  function fullXi(): PlacedPlayer[] {
    const mk = (id: string, position: 'GK' | 'DEF' | 'MID' | 'ATT', slotIndex: number): PlacedPlayer =>
      ({ id, surname: id, position, detailedPosition: null, fitness: 100, slotIndex })
    return [
      mk('gk', 'GK', 0),
      mk('d1', 'DEF', 1), mk('d2', 'DEF', 2), mk('d3', 'DEF', 3), mk('d4', 'DEF', 4),
      mk('m1', 'MID', 5), mk('m2', 'MID', 6), mk('m3', 'MID', 7),
      mk('a1', 'ATT', 8), mk('a2', 'ATT', 9), mk('a3', 'ATT', 10),
    ]
  }

  function renderMenuable(players = fullXi()) {
    const onSlotClick = vi.fn()
    const onSlotContextMenu = vi.fn()
    const onSwap = vi.fn()
    const { container } = render(
      <Pitch
        home={{ formation: '4-3-3', kit, showNames: true, players }}
        onSwap={onSwap}
        onSlotClick={onSlotClick}
        onSlotContextMenu={onSlotContextMenu}
      />,
    )
    const pitch = screen.getByTestId('pitch')
    vi.spyOn(pitch, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 600, width: 400, height: 600, x: 0, y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    const token = (slot: number) => container.querySelector(`[data-slot-index="${slot}"]`)!
    return { onSwap, onSlotClick, onSlotContextMenu, token }
  }

  it('right-click on an occupied slot reports slotIndex and coordinates', () => {
    const { onSlotContextMenu, token } = renderMenuable()
    fireEvent.contextMenu(token(1), { clientX: 64, clientY: 474 })
    expect(onSlotContextMenu).toHaveBeenCalledWith(1, 64, 474)
  })

  it('right-click on an empty slot does nothing', () => {
    const players = fullXi().filter(p => p.slotIndex !== 10)
    const { onSlotContextMenu, token } = renderMenuable(players)
    fireEvent.contextMenu(token(10), { clientX: 200, clientY: 192 })
    expect(onSlotContextMenu).not.toHaveBeenCalled()
  })

  describe('long-press', () => {
    beforeEach(() => { vi.useFakeTimers() })
    afterEach(() => { vi.useRealTimers() })

    it('a 500ms hold fires the context menu and suppresses the tap', () => {
      const { onSlotContextMenu, onSlotClick, onSwap, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).toHaveBeenCalledWith(1, 64, 474)
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      expect(onSlotClick).not.toHaveBeenCalled()
      expect(onSwap).not.toHaveBeenCalled()
    })

    it('a short tap still fires onSlotClick and no context menu', () => {
      const { onSlotContextMenu, onSlotClick, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      act(() => { vi.advanceTimersByTime(200) })
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      expect(onSlotClick).toHaveBeenCalledWith(1)
      expect(onSlotContextMenu).not.toHaveBeenCalled()
    })

    it('a hold that moves past the threshold is a drag, not a context menu', () => {
      const { onSlotContextMenu, onSwap, token } = renderMenuable()
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      fireEvent.pointerMove(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).not.toHaveBeenCalled()
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 200, clientY: 192 })
      expect(onSwap).toHaveBeenCalledWith(1, 9)
    })

    it('a 500ms hold on an empty slot does nothing', () => {
      const players = fullXi().filter(p => p.slotIndex !== 10)
      const { onSlotContextMenu, token } = renderMenuable(players)
      fireEvent.pointerDown(token(10), { pointerId: 1, clientX: 200, clientY: 192 })
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).not.toHaveBeenCalled()
    })

    it('a second pointer starting mid-hold does not orphan the first timer or swallow its tap', () => {
      const { onSlotContextMenu, onSlotClick, token } = renderMenuable()
      // Finger A presses slot 1, arming its long-press timer.
      fireEvent.pointerDown(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      // Before A's timer fires, finger B presses slot 3 — this must cancel A's still-pending
      // timer rather than silently overwrite the shared ref.
      fireEvent.pointerDown(token(3), { pointerId: 2, clientX: 200, clientY: 300 })
      // Finger A lifts as a quick tap.
      fireEvent.pointerUp(token(1), { pointerId: 1, clientX: 64, clientY: 474 })
      // Advance past the 500ms mark from A's original press. If A's timer had been orphaned it
      // would fire here, opening the context menu for a slot the user already released.
      act(() => { vi.advanceTimersByTime(500) })
      expect(onSlotContextMenu).not.toHaveBeenCalled()
      // Finger B lifts as a legitimate short tap on slot 3 — must still resolve as a tap, not be
      // swallowed by a stale longPressFired flag set by A's orphaned timer.
      fireEvent.pointerUp(token(3), { pointerId: 2, clientX: 200, clientY: 300 })
      expect(onSlotClick).toHaveBeenCalledWith(3)
      expect(onSlotContextMenu).not.toHaveBeenCalled()
    })
  })
})
