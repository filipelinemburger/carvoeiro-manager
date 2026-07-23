import { describe, it, expect } from 'vitest'
import { pitchSlots, FORMATION_CODES, LAYOUTS } from './pitchSlots'
import { FORMATION_SHAPE } from '../squad/SquadPage'
import { DETAILED_TO_COARSE } from '../players/positionLabels'

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
