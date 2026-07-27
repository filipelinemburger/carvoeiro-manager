import { describe, it, expect } from 'vitest'
import { pitchSlots, FORMATION_CODES, LAYOUTS } from './pitchSlots'
import { FORMATION_SHAPE } from '../squad/SquadPage'
import { DETAILED_TO_COARSE } from '../players/positionLabels'
import type { DetailedPosition } from '../../api/types'

// Derive the canonical coarse-band shape straight from the formation code's digits, exactly like
// the backend FormationSlots guard: GK = 1, DEF = first digit, ATT = last digit, MID = the digits
// in between summed. Together with the backend guard this keeps the two definitions in lockstep.
function shapeFromCode(code: string): { GK: number; DEF: number; MID: number; ATT: number } {
  const digits = code.split('-').map(Number)
  const DEF = digits[0]
  const ATT = digits[digits.length - 1]
  const MID = digits.slice(1, -1).reduce((a, b) => a + b, 0)
  return { GK: 1, DEF, MID, ATT }
}

describe('pitchSlots', () => {
  it('exposes all 16 formation codes', () => {
    expect(FORMATION_CODES).toHaveLength(16)
    expect(new Set(FORMATION_CODES).size).toBe(16)
  })

  it.each(FORMATION_CODES)('%s has 11 slots whose coarse-band counts match the code digits', code => {
    const slots = pitchSlots(code)
    expect(slots).toHaveLength(11)
    const shape = shapeFromCode(code)
    const count = (l: string) => slots.filter(s => s.line === l).length
    expect(count('GK')).toBe(shape.GK)
    expect(count('DEF')).toBe(shape.DEF)
    expect(count('MID')).toBe(shape.MID)
    expect(count('ATT')).toBe(shape.ATT)
  })

  it.each(FORMATION_CODES)('%s tags every slot with accepted positions inside its coarse band', code => {
    const slots = pitchSlots(code)
    // Every slot has at least one accepted detailed position, and all of them belong to the slot's line.
    for (const s of slots) {
      expect(s.accepted.length).toBeGreaterThan(0)
      for (const dp of s.accepted) {
        expect(DETAILED_TO_COARSE[dp]).toBe(s.line)
      }
    }
    // The accepted-derived band counts also equal the code digits.
    const shape = shapeFromCode(code)
    const bandCount = (band: string) =>
      slots.filter(s => DETAILED_TO_COARSE[s.accepted[0]] === band).length
    expect(bandCount('GK')).toBe(shape.GK)
    expect(bandCount('DEF')).toBe(shape.DEF)
    expect(bandCount('MID')).toBe(shape.MID)
    expect(bandCount('ATT')).toBe(shape.ATT)
  })

  it.each(FORMATION_CODES)('%s keeps every coordinate inside the pitch and non-overlapping', code => {
    const slots = pitchSlots(code)
    for (const s of slots) {
      expect(s.x).toBeGreaterThanOrEqual(0)
      expect(s.x).toBeLessThanOrEqual(1)
      expect(s.y).toBeGreaterThanOrEqual(0)
      expect(s.y).toBeLessThanOrEqual(1)
    }
    const keys = slots.map(s => `${s.x.toFixed(3)},${s.y.toFixed(3)}`)
    expect(new Set(keys).size).toBe(11)
  })

  it('falls back to 4-4-2 for an unknown code', () => {
    expect(pitchSlots('9-0-1')).toEqual(pitchSlots('4-4-2'))
  })

  it('lists formation codes in ascending order (numeric-aware pt-BR compare)', () => {
    const sorted = [...FORMATION_CODES].sort((a, b) => a.localeCompare(b, 'pt-BR', { numeric: true }))
    expect(FORMATION_CODES).toEqual(sorted)
    // Sanity check on the numeric-aware ordering: 4-1-4-1 sorts before 4-4-2.
    expect(FORMATION_CODES.indexOf('4-1-4-1')).toBeLessThan(FORMATION_CODES.indexOf('4-4-2'))
  })

  it('keeps the same set of formation codes as LAYOUTS (order is display-only)', () => {
    expect(new Set(FORMATION_CODES)).toEqual(new Set(Object.keys(LAYOUTS)))
  })

  it('stays in sync with SquadPage FORMATION_SHAPE keys and coarse bands', () => {
    expect(new Set(Object.keys(FORMATION_SHAPE))).toEqual(new Set(Object.keys(LAYOUTS)))
    // FORMATION_SHAPE (outfield only) must agree with pitchSlots' coarse bands for each code.
    for (const code of FORMATION_CODES) {
      const shape = shapeFromCode(code)
      expect(FORMATION_SHAPE[code]).toEqual({ DEF: shape.DEF, MID: shape.MID, ATT: shape.ATT })
    }
  })
})

// The backend persists `slot_index` (0-10) per lineup starter, meaning "position N in the ordered
// list pitchSlots(formation) returns". The tests above only check per-line COUNTS (e.g. 4-3-3 has
// 4 DEF slots) and that each slot's `accepted` positions belong to the right coarse line — neither
// catches someone silently reordering slots WITHIN a line (e.g. swapping the two ZAGUEIRO slots'
// position, or moving VOLANTE to a different index inside MID). That kind of reorder would leave
// every existing lineup's already-persisted slot_index pointing at a different slot than when it
// was saved: a silent data-meaning corruption with no error anywhere.
//
// This golden table pins the EXACT ordered sequence of `accepted` tags pitchSlots() returns today,
// per formation, as a literal fingerprint. It is intentionally copied straight from pitchSlots.ts's
// current LAYOUTS output (not re-derived from shared constants) so a within-line reorder changes the
// literal array here and fails immediately and specifically, rather than passing silently.
const GOLDEN_ACCEPTED_SEQUENCE: Record<string, DetailedPosition[][]> = {
  '4-4-2': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'],
  ],
  '4-3-3': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['PONTA'], ['ATACANTE', 'FALSO_9'], ['PONTA'],
  ],
  '3-5-2': [
    ['GOLEIRO'],
    ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'],
  ],
  '5-3-2': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'],
  ],
  '4-5-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '4-2-3-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['VOLANTE'],
    ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '4-1-4-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'],
    ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '4-3-2-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '3-4-3': [
    ['GOLEIRO'],
    ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['PONTA'], ['ATACANTE', 'FALSO_9'], ['PONTA'],
  ],
  '4-4-1-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '5-4-1': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '4-2-4': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'],
    ['PONTA'], ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'], ['PONTA'],
  ],
  '3-4-2-1': [
    ['GOLEIRO'],
    ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'],
  ],
  '4-1-2-1-2': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'],
    ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'],
  ],
  '5-2-3': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'],
    ['PONTA'], ['ATACANTE', 'FALSO_9'], ['PONTA'],
  ],
  '4-3-1-2': [
    ['GOLEIRO'],
    ['LATERAL_DIREITO'], ['ZAGUEIRO'], ['ZAGUEIRO'], ['LATERAL_ESQUERDO'],
    ['VOLANTE'], ['MEIA_ARMADOR'], ['MEIA_ARMADOR'],
    ['MEIA_ARMADOR'],
    ['ATACANTE', 'FALSO_9'], ['ATACANTE', 'FALSO_9'],
  ],
}

describe('pitchSlots golden slot order (regression guard for persisted slot_index)', () => {
  it('covers every known formation code with a golden sequence', () => {
    expect(new Set(Object.keys(GOLDEN_ACCEPTED_SEQUENCE))).toEqual(new Set(FORMATION_CODES))
  })

  it.each(FORMATION_CODES)('%s has the exact same ordered accepted-tag sequence as today', code => {
    const slots = pitchSlots(code)
    // A literal, hard-coded comparison — not a count or "belongs to the right line" check — so
    // reordering slots within (or across) a line fails this test even though every other test in
    // this file keeps passing.
    expect(slots.map(s => s.accepted)).toEqual(GOLDEN_ACCEPTED_SEQUENCE[code])
  })
})
