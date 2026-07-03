import { describe, it, expect } from 'vitest'
import { moraleMood, teamMorale } from './morale'

describe('moraleMood', () => {
  it('maps a value to its mood bucket', () => {
    expect(moraleMood(-5).label).toBe('Desanimado')
    expect(moraleMood(-3).label).toBe('Desanimado')
    expect(moraleMood(-1).label).toBe('Descontente')
    expect(moraleMood(0).label).toBe('Neutro')
    expect(moraleMood(2).label).toBe('Contente')
    expect(moraleMood(3).label).toBe('Radiante')
    expect(moraleMood(5).label).toBe('Radiante')
  })
})

describe('teamMorale', () => {
  it('averages and rounds, and is 0 for an empty squad', () => {
    expect(teamMorale([])).toBe(0)
    expect(teamMorale([1, 2, 3])).toBe(2)      // 2.0
    expect(teamMorale([-2, -2, -1])).toBe(-2)  // -1.67 -> -2
  })
})
