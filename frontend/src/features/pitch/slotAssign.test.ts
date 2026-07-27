import { describe, it, expect } from 'vitest'
import { assignSlots, type AssignablePlayer } from './slotAssign'
import { pitchSlots } from './pitchSlots'
import type { DetailedPosition } from '../../api/types'

function p(id: string, position: AssignablePlayer['position'],
           detailedPosition: DetailedPosition | null, overall: number): AssignablePlayer {
  return { id, position, detailedPosition, overall }
}

// A well-formed 4-4-2 XI: one exact candidate per slot, in slot order.
const wellFormed442: AssignablePlayer[] = [
  p('gk', 'GK', 'GOLEIRO', 75),
  p('ld', 'DEF', 'LATERAL_DIREITO', 74),
  p('z1', 'DEF', 'ZAGUEIRO', 73),
  p('z2', 'DEF', 'ZAGUEIRO', 72),
  p('le', 'DEF', 'LATERAL_ESQUERDO', 71),
  p('vol', 'MID', 'VOLANTE', 70),
  p('m1', 'MID', 'MEIA_ARMADOR', 69),
  p('m2', 'MID', 'MEIA_ARMADOR', 68),
  p('m3', 'MID', 'MEIA_ARMADOR', 67),
  p('a1', 'ATT', 'ATACANTE', 66),
  p('a2', 'ATT', 'ATACANTE', 65),
]

describe('assignSlots', () => {
  it('assigns a well-formed XI so every player sits in a slot accepting his detailed position', () => {
    const result = assignSlots(wellFormed442, '4-4-2')
    const slots = pitchSlots('4-4-2')
    expect(Object.keys(result)).toHaveLength(11)
    expect(new Set(Object.values(result)).size).toBe(11)
    for (const player of wellFormed442) {
      expect(slots[result[player.id]].accepted).toContain(player.detailedPosition)
    }
    expect(result['gk']).toBe(0)
  })

  it('lets an exact detailed match win its slot over a higher-rated same-band player', () => {
    const players = [
      ...wellFormed442.filter(x => x.id !== 'ld'),
      p('lowLd', 'DEF', 'LATERAL_DIREITO', 55),
    ]
    const result = assignSlots(players, '4-4-2')
    // Slot 1 is the right-back slot in 4-4-2: the only LATERAL_DIREITO takes it despite
    // every other defender out-rating him.
    expect(result['lowLd']).toBe(1)
  })

  it('fills every slot from tier-3 when nobody fits (all-zagueiro squad)', () => {
    const players = Array.from({ length: 11 }, (_, i) =>
      p(`z${i}`, 'DEF', 'ZAGUEIRO', 80 - i))
    const result = assignSlots(players, '4-4-2')
    expect(Object.keys(result)).toHaveLength(11)
    expect(new Set(Object.values(result)).size).toBe(11)
  })

  it('is deterministic across repeated calls', () => {
    const shuffled = [...wellFormed442].reverse()
    expect(assignSlots(shuffled, '4-3-3')).toEqual(assignSlots(shuffled, '4-3-3'))
  })

  it('assigns a partial squad (fewer than 11) to distinct slots without throwing', () => {
    const five = wellFormed442.slice(0, 5)
    const result = assignSlots(five, '4-4-2')
    expect(Object.keys(result)).toHaveLength(5)
    expect(new Set(Object.values(result)).size).toBe(5)
  })
})
