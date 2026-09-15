import { describe, it, expect } from 'vitest'
import { workingRoster, baselineArrangement, swapSlots, sameSlots, dropMovedRoles, type RoleOverrides } from './liveArrangement'
import type { LivePlayer } from '../../api/types'

function livePlayer(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Jogador', position: 'MID', overall: 70, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'BOX_TO_BOX',
    slotIndex: null, detailedPosition: null, altPositions: [],
    ...overrides,
  }
}

describe('workingRoster', () => {
  it('replaces a queued outgoing player with the incoming one, who inherits the slot index', () => {
    const out = livePlayer({ playerId: 'out', slotIndex: 6 })
    const stays = livePlayer({ playerId: 'stays', slotIndex: 0 })
    const incoming = livePlayer({ playerId: 'in', onPitch: false, slotIndex: null })

    const roster = workingRoster([stays, out], [{ out, in: incoming }])

    expect(roster.map(p => p.playerId)).toEqual(['stays', 'in'])
    expect(roster[1].slotIndex).toBe(6)
  })
})

describe('baselineArrangement', () => {
  it('uses the server indexes when the formation is unchanged and every player has one', () => {
    const roster = [livePlayer({ playerId: 'a', slotIndex: 3 }), livePlayer({ playerId: 'b', slotIndex: 7 })]

    expect(baselineArrangement(roster, '4-4-2', '4-4-2')).toEqual({ a: 3, b: 7 })
  })

  it('reassigns every player to a distinct slot when the formation changed', () => {
    const roster = [
      livePlayer({ playerId: 'gk', position: 'GK', detailedPosition: 'GOLEIRO', slotIndex: 0 }),
      livePlayer({ playerId: 'st', position: 'ATT', detailedPosition: 'ATACANTE', slotIndex: 9 }),
    ]

    const slots = baselineArrangement(roster, '4-3-3', '4-4-2')

    expect(Object.keys(slots).sort()).toEqual(['gk', 'st'])
    expect(new Set(Object.values(slots)).size).toBe(2)
    expect(slots.gk).toBe(0)
  })

  it('reassigns when any player lacks a server index', () => {
    const roster = [livePlayer({ playerId: 'a', slotIndex: 3 }), livePlayer({ playerId: 'b', slotIndex: null })]

    const slots = baselineArrangement(roster, '4-4-2', '4-4-2')

    expect(Object.keys(slots).sort()).toEqual(['a', 'b'])
  })
})

describe('swapSlots', () => {
  it('exchanges the two occupants', () => {
    expect(swapSlots({ a: 1, b: 2 }, 1, 2)).toEqual({ a: 2, b: 1 })
  })

  it('moves a player into an empty slot', () => {
    expect(swapSlots({ a: 1 }, 1, 5)).toEqual({ a: 5 })
  })

  it('leaves the map untouched when the origin slot is empty', () => {
    const slots = { a: 1 }
    expect(swapSlots(slots, 4, 5)).toBe(slots)
  })
})

describe('sameSlots', () => {
  it('is true only for identical assignments', () => {
    expect(sameSlots({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true)
    expect(sameSlots({ a: 1, b: 2 }, { a: 2, b: 1 })).toBe(false)
    expect(sameSlots({ a: 1 }, { a: 1, b: 2 })).toBe(false)
  })
})

describe('dropMovedRoles', () => {
  it('drops the override for both swapped players and keeps an unmoved player\'s override', () => {
    const before = { a: 1, b: 2, c: 3 }
    const after = { a: 2, b: 1, c: 3 }
    const roles: RoleOverrides = { a: 'WINGER', b: 'FULLBACK', c: 'STOPPER' }

    expect(dropMovedRoles(before, after, roles)).toEqual({ c: 'STOPPER' })
  })

  it('drops the mover\'s override on a move into an empty slot', () => {
    const before = { a: 1 }
    const after = { a: 5 }
    const roles: RoleOverrides = { a: 'WINGER' }

    expect(dropMovedRoles(before, after, roles)).toEqual({})
  })
})
