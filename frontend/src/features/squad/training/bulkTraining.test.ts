import { describe, it, expect } from 'vitest'
import { applyBulkRules, type BulkTrainingRule, type TrainingAssignmentsState } from './bulkTraining'
import type { PlayerResponse } from '../../../api/types'

// Only id, detailedPosition and altPositions matter here; the rest is cast in.
const player = (
  id: string,
  detailedPosition: PlayerResponse['detailedPosition'],
  altPositions?: PlayerResponse['altPositions'],
): PlayerResponse => ({ id, detailedPosition, altPositions } as PlayerResponse)

const PLAYERS = [
  player('gk1', 'GOLEIRO'),
  player('gk2', 'GOLEIRO'),
  player('cb1', 'ZAGUEIRO'),
  player('lb1', 'LATERAL_ESQUERDO', ['ZAGUEIRO']),
  player('legacy', null),
]

const BASE: TrainingAssignmentsState = {
  gk1: { focus: 'PASSE', intensity: 'LEVE' },
  gk2: { focus: null, intensity: 'NORMAL' },
  cb1: { focus: 'DRIBLE', intensity: 'INTENSA' },
  lb1: { focus: 'CRUZAMENTO', intensity: 'NORMAL' },
  legacy: { focus: 'FORCA', intensity: 'LEVE' },
}

const gkRule: BulkTrainingRule = { position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'INTENSA' }

describe('applyBulkRules', () => {
  it('applies a rule to every player whose main position matches', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.gk1).toEqual({ focus: 'REFLEXOS', intensity: 'INTENSA' })
    expect(next.gk2).toEqual({ focus: 'REFLEXOS', intensity: 'INTENSA' })
  })

  it('leaves players matched by no rule untouched', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.cb1).toEqual({ focus: 'DRIBLE', intensity: 'INTENSA' })
  })

  it('ignores alternate positions — main position only', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      { position: 'ZAGUEIRO', focus: 'MARCACAO', intensity: 'NORMAL' },
    ])
    expect(next.cb1).toEqual({ focus: 'MARCACAO', intensity: 'NORMAL' })
    // lb1 lists ZAGUEIRO as an ALTERNATE, so the rule must not reach them.
    expect(next.lb1).toEqual({ focus: 'CRUZAMENTO', intensity: 'NORMAL' })
  })

  it('never touches a player without a detailed position', () => {
    const next = applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(next.legacy).toEqual({ focus: 'FORCA', intensity: 'LEVE' })
  })

  it('treats a null focus as a deliberate clear, not a skip', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      { position: 'GOLEIRO', focus: null, intensity: 'LEVE' },
    ])
    expect(next.gk1).toEqual({ focus: null, intensity: 'LEVE' })
  })

  it('applies several rules in one pass', () => {
    const next = applyBulkRules(BASE, PLAYERS, [
      gkRule,
      { position: 'ZAGUEIRO', focus: 'DESARME', intensity: 'LEVE' },
    ])
    expect(next.gk1.focus).toBe('REFLEXOS')
    expect(next.cb1).toEqual({ focus: 'DESARME', intensity: 'LEVE' })
  })

  it('does not mutate the input record', () => {
    applyBulkRules(BASE, PLAYERS, [gkRule])
    expect(BASE.gk1).toEqual({ focus: 'PASSE', intensity: 'LEVE' })
  })

  it('returns the assignments unchanged when there are no rules', () => {
    expect(applyBulkRules(BASE, PLAYERS, [])).toEqual(BASE)
  })
})
