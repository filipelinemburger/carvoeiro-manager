import { describe, it, expect } from 'vitest'
import { pitchSlots, FORMATION_CODES } from './pitchSlots'

const EXPECTED_SHAPE: Record<string, { DEF: number; MID: number; ATT: number }> = {
  '4-4-2': { DEF: 4, MID: 4, ATT: 2 }, '4-3-3': { DEF: 4, MID: 3, ATT: 3 },
  '3-5-2': { DEF: 3, MID: 5, ATT: 2 }, '5-3-2': { DEF: 5, MID: 3, ATT: 2 },
  '4-5-1': { DEF: 4, MID: 5, ATT: 1 }, '4-2-3-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-1-4-1': { DEF: 4, MID: 5, ATT: 1 }, '4-3-2-1': { DEF: 4, MID: 5, ATT: 1 },
  '3-4-3': { DEF: 3, MID: 4, ATT: 3 }, '4-4-1-1': { DEF: 4, MID: 4, ATT: 2 },
  '5-4-1': { DEF: 5, MID: 4, ATT: 1 }, '4-2-4': { DEF: 4, MID: 2, ATT: 4 },
  '3-4-2-1': { DEF: 3, MID: 6, ATT: 1 }, '4-1-2-1-2': { DEF: 4, MID: 4, ATT: 2 },
  '5-2-3': { DEF: 5, MID: 2, ATT: 3 }, '4-3-1-2': { DEF: 4, MID: 4, ATT: 2 },
}

describe('pitchSlots', () => {
  it('exposes all 16 formation codes', () => {
    expect(FORMATION_CODES).toHaveLength(16)
    expect(new Set(FORMATION_CODES).size).toBe(16)
  })

  it.each(FORMATION_CODES)('%s has 11 slots with the correct line counts', code => {
    const slots = pitchSlots(code)
    expect(slots).toHaveLength(11)
    const count = (l: string) => slots.filter(s => s.line === l).length
    expect(count('GK')).toBe(1)
    expect(count('DEF')).toBe(EXPECTED_SHAPE[code].DEF)
    expect(count('MID')).toBe(EXPECTED_SHAPE[code].MID)
    expect(count('ATT')).toBe(EXPECTED_SHAPE[code].ATT)
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
})
