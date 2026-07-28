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
  // 4-4-2's MID row is [MEIA_DIREITA, VOLANTE, MEIA_CENTRAL, MEIA_ESQUERDA] under the 12-position
  // model (see pitchSlots.ts) — m1/m2/m3 carry the wide/central detailed positions so this XI is
  // genuinely well-formed (one exact natural per slot), not three interchangeable MEIA_ARMADORes.
  p('m1', 'MID', 'MEIA_DIREITA', 69),
  p('m2', 'MID', 'MEIA_CENTRAL', 68),
  p('m3', 'MID', 'MEIA_ESQUERDA', 67),
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

  it('treats an alternate position as NATURAL, beating a distance-fallback candidate', () => {
    // Replace the exact LATERAL_ESQUERDO player with one whose PRIMARY is ZAGUEIRO but who lists
    // LATERAL_ESQUERDO as an alternate — the alternate should count as natural for the LE slot.
    const players: AssignablePlayer[] = [
      ...wellFormed442.filter(x => x.id !== 'le'),
      { id: 'altLe', position: 'DEF', detailedPosition: 'ZAGUEIRO', altPositions: ['LATERAL_ESQUERDO'], overall: 60 },
    ]
    const result = assignSlots(players, '4-4-2')
    const slots = pitchSlots('4-4-2')
    const leSlotIndex = slots.findIndex(s => s.accepted.includes('LATERAL_ESQUERDO'))
    expect(result['altLe']).toBe(leSlotIndex)
  })

  it('falls back to the tactically closest player when no natural candidate exists for a slot', () => {
    // Remove the only natural VOLANTE, so the VOL slot has zero naturals and is filled last from
    // whoever is left. The leftover here is a ZAGUEIRO (tactical distance 1) rather than an
    // attacker — the fallback still picks the closest player, just from genuine leftovers, and
    // the two real zagueiros keep the centre-back slots they are natural for.
    const players: AssignablePlayer[] = [
      ...wellFormed442.filter(x => x.id !== 'vol'),
      p('extraZag', 'DEF', 'ZAGUEIRO', 50),
    ]
    const result = assignSlots(players, '4-4-2')
    const slots = pitchSlots('4-4-2')
    const volSlotIndex = slots.findIndex(s => s.accepted.includes('VOLANTE'))
    const zagSlots = slots.map((s, i) => ({ s, i })).filter(x => x.s.accepted.includes('ZAGUEIRO')).map(x => x.i)

    expect(result['extraZag']).toBe(volSlotIndex)
    expect(zagSlots).toContain(result['z1'])
    expect(zagSlots).toContain(result['z2'])
  })

  it('a zero-natural slot never steals another slot\'s only natural', () => {
    // Mirrors the backend SlotAssignerNaturalFirstTest regression: with no keeper in the pool the
    // GK slot has ZERO naturals. Sorted "scarcest first" by raw count it would run before every
    // other slot and its distance fallback would grab a genuine natural — here the sole
    // LATERAL_ESQUERDO — leaving the LE slot to an improviser. Zero-natural slots must sort LAST.
    // Every outfielder is distance 3 from GOLEIRO, so the GK fallback ties on distance and
    // breaks by overall desc — making the sole LATERAL_ESQUERDO the top-rated player is what
    // puts the two rules in conflict.
    const players: AssignablePlayer[] = [
      ...wellFormed442.filter(x => x.id !== 'gk' && x.id !== 'le'),
      p('le', 'DEF', 'LATERAL_ESQUERDO', 99),
      p('spareZag', 'DEF', 'ZAGUEIRO', 40),
    ]
    const result = assignSlots(players, '4-4-2')
    const slots = pitchSlots('4-4-2')
    const leSlotIndex = slots.findIndex(s => s.accepted.includes('LATERAL_ESQUERDO'))
    const gkSlotIndex = slots.findIndex(s => s.accepted.includes('GOLEIRO'))

    expect(result['le']).toBe(leSlotIndex)
    // The improvised keeper is a leftover, never a slot's only natural.
    expect(Object.keys(result).find(id => result[id] === gkSlotIndex)).toBe('spareZag')
  })
})
