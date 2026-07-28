import { describe, it, expect } from 'vitest'
import { ARCHETYPE_LABEL, FOCUS_LABEL, ARCHETYPES_FOR_POSITION, FOCUSES_FOR, defaultArchetypeFor } from './roleLabels'
import type { DetailedPosition, RoleArchetype, RoleFocus } from '../../api/types'

const ALL_POSITIONS: DetailedPosition[] = [
  'GOLEIRO', 'ZAGUEIRO', 'LATERAL_DIREITO', 'LATERAL_ESQUERDO', 'VOLANTE', 'MEIA_CENTRAL',
  'MEIA_ARMADOR', 'MEIA_DIREITA', 'MEIA_ESQUERDA', 'PONTA_DIREITA', 'PONTA_ESQUERDA', 'ATACANTE',
]

const ALL_ARCHETYPES: RoleArchetype[] = [
  'GOALKEEPER', 'STOPPER', 'FULLBACK', 'BALL_PLAYING_DEFENDER', 'HOLDING', 'BOX_TO_BOX', 'PLAYMAKER',
  'POACHER', 'TARGET_FORWARD', 'WINGER', 'SWEEPER_KEEPER', 'BALL_PLAYING_KEEPER', 'WINGBACK',
  'FALSEBACK', 'ATTACKING_WINGBACK', 'INVERTED_WINGBACK', 'DEFENDER', 'WIDE_BACK',
  'DEEP_LYING_PLAYMAKER', 'CENTRE_HALF', 'WIDE_HALF', 'BOX_CRASHER', 'HALF_WINGER',
  'SHADOW_STRIKER', 'CLASSIC_10', 'WIDE_PLAYMAKER', 'WIDE_MIDFIELDER', 'INSIDE_FORWARD',
  'ADVANCED_FORWARD', 'FALSE_9',
]

const ALL_FOCUSES: RoleFocus[] = [
  'DEFEND', 'BALANCED', 'ATTACK', 'BUILD_UP', 'SUPPORT', 'ROAMING', 'VERSATILE', 'AGGRESSIVE',
  'BALL_WINNING', 'WIDE',
]

// Hand-transcribed straight from the backend `RoleArchetype.MATRIX` (archetype → position →
// focuses, default first) — written independently of roleLabels.ts's own MATRIX so this test
// actually catches drift between the two sides, the frontend analogue of the backend's
// RoleArchetypeFutbinSyncTest. Do NOT derive this table from roleLabels.ts's exports.
const EXPECTED_MATRIX: Record<RoleArchetype, Partial<Record<DetailedPosition, RoleFocus[]>>> = {
  GOALKEEPER: { GOLEIRO: ['DEFEND', 'BALANCED'] },
  SWEEPER_KEEPER: { GOLEIRO: ['BALANCED', 'BUILD_UP'] },
  BALL_PLAYING_KEEPER: { GOLEIRO: ['BUILD_UP'] },
  FULLBACK: {
    LATERAL_DIREITO: ['BALANCED', 'DEFEND', 'VERSATILE'],
    LATERAL_ESQUERDO: ['BALANCED', 'DEFEND', 'VERSATILE'],
  },
  WINGBACK: {
    LATERAL_DIREITO: ['BALANCED', 'SUPPORT'],
    LATERAL_ESQUERDO: ['BALANCED', 'SUPPORT'],
  },
  FALSEBACK: {
    LATERAL_DIREITO: ['BALANCED', 'DEFEND'],
    LATERAL_ESQUERDO: ['BALANCED', 'DEFEND'],
  },
  ATTACKING_WINGBACK: {
    LATERAL_DIREITO: ['SUPPORT', 'ATTACK'],
    LATERAL_ESQUERDO: ['SUPPORT', 'ATTACK'],
  },
  INVERTED_WINGBACK: {
    LATERAL_DIREITO: ['BUILD_UP', 'ATTACK'],
    LATERAL_ESQUERDO: ['BUILD_UP', 'ATTACK'],
  },
  DEFENDER: { ZAGUEIRO: ['DEFEND', 'BALANCED'] },
  STOPPER: { ZAGUEIRO: ['BALANCED', 'AGGRESSIVE'] },
  BALL_PLAYING_DEFENDER: { ZAGUEIRO: ['DEFEND', 'BUILD_UP', 'AGGRESSIVE'] },
  WIDE_BACK: { ZAGUEIRO: ['DEFEND', 'SUPPORT', 'AGGRESSIVE'] },
  HOLDING: {
    VOLANTE: ['DEFEND', 'ROAMING', 'BALL_WINNING'],
    MEIA_CENTRAL: ['DEFEND', 'BALL_WINNING'],
  },
  DEEP_LYING_PLAYMAKER: {
    VOLANTE: ['DEFEND', 'ROAMING', 'BUILD_UP'],
    MEIA_CENTRAL: ['DEFEND', 'BUILD_UP'],
  },
  CENTRE_HALF: { VOLANTE: ['DEFEND'] },
  WIDE_HALF: { VOLANTE: ['DEFEND', 'BUILD_UP'] },
  BOX_CRASHER: { VOLANTE: ['BALANCED'] },
  BOX_TO_BOX: { MEIA_CENTRAL: ['BALANCED', 'BALL_WINNING'] },
  PLAYMAKER: {
    MEIA_CENTRAL: ['ATTACK', 'ROAMING'],
    MEIA_ARMADOR: ['BALANCED', 'ROAMING', 'BUILD_UP'],
  },
  HALF_WINGER: {
    MEIA_CENTRAL: ['BALANCED', 'ATTACK', 'SUPPORT'],
    MEIA_ARMADOR: ['BALANCED', 'ATTACK', 'ROAMING'],
  },
  SHADOW_STRIKER: { MEIA_ARMADOR: ['ATTACK'] },
  CLASSIC_10: { MEIA_ARMADOR: ['ATTACK', 'WIDE', 'VERSATILE'] },
  WINGER: {
    MEIA_DIREITA: ['BALANCED', 'ATTACK'],
    MEIA_ESQUERDA: ['BALANCED', 'ATTACK'],
    PONTA_DIREITA: ['BALANCED', 'ATTACK', 'VERSATILE'],
    PONTA_ESQUERDA: ['BALANCED', 'ATTACK', 'VERSATILE'],
  },
  WIDE_PLAYMAKER: {
    MEIA_DIREITA: ['ATTACK', 'BUILD_UP'],
    MEIA_ESQUERDA: ['ATTACK', 'BUILD_UP'],
    PONTA_DIREITA: ['ATTACK', 'BUILD_UP'],
    PONTA_ESQUERDA: ['ATTACK', 'BUILD_UP'],
  },
  WIDE_MIDFIELDER: {
    MEIA_DIREITA: ['SUPPORT', 'DEFEND', 'BUILD_UP'],
    MEIA_ESQUERDA: ['SUPPORT', 'DEFEND', 'BUILD_UP'],
  },
  INSIDE_FORWARD: {
    MEIA_DIREITA: ['BALANCED', 'ATTACK'],
    MEIA_ESQUERDA: ['BALANCED', 'ATTACK'],
    PONTA_DIREITA: ['BALANCED', 'ATTACK', 'ROAMING'],
    PONTA_ESQUERDA: ['BALANCED', 'ATTACK', 'ROAMING'],
  },
  ADVANCED_FORWARD: { ATACANTE: ['ATTACK', 'VERSATILE', 'SUPPORT'] },
  TARGET_FORWARD: { ATACANTE: ['ATTACK', 'BALANCED', 'WIDE'] },
  FALSE_9: { ATACANTE: ['BUILD_UP', 'ATTACK'] },
  POACHER: { ATACANTE: ['ATTACK', 'SUPPORT', 'VERSATILE'] },
}

// Hand-transcribed archetype→position edges, grouped by position, in RoleArchetype enum
// declaration order — the inverse of EXPECTED_MATRIX above, written independently.
const EXPECTED_ARCHETYPES_FOR_POSITION: Record<DetailedPosition, RoleArchetype[]> = {
  GOLEIRO: ['GOALKEEPER', 'SWEEPER_KEEPER', 'BALL_PLAYING_KEEPER'],
  ZAGUEIRO: ['STOPPER', 'BALL_PLAYING_DEFENDER', 'DEFENDER', 'WIDE_BACK'],
  LATERAL_DIREITO: ['FULLBACK', 'WINGBACK', 'FALSEBACK', 'ATTACKING_WINGBACK', 'INVERTED_WINGBACK'],
  LATERAL_ESQUERDO: ['FULLBACK', 'WINGBACK', 'FALSEBACK', 'ATTACKING_WINGBACK', 'INVERTED_WINGBACK'],
  VOLANTE: ['HOLDING', 'DEEP_LYING_PLAYMAKER', 'CENTRE_HALF', 'WIDE_HALF', 'BOX_CRASHER'],
  MEIA_CENTRAL: ['HOLDING', 'BOX_TO_BOX', 'PLAYMAKER', 'DEEP_LYING_PLAYMAKER', 'HALF_WINGER'],
  MEIA_ARMADOR: ['PLAYMAKER', 'HALF_WINGER', 'SHADOW_STRIKER', 'CLASSIC_10'],
  MEIA_DIREITA: ['WINGER', 'WIDE_PLAYMAKER', 'WIDE_MIDFIELDER', 'INSIDE_FORWARD'],
  MEIA_ESQUERDA: ['WINGER', 'WIDE_PLAYMAKER', 'WIDE_MIDFIELDER', 'INSIDE_FORWARD'],
  PONTA_DIREITA: ['WINGER', 'WIDE_PLAYMAKER', 'INSIDE_FORWARD'],
  PONTA_ESQUERDA: ['WINGER', 'WIDE_PLAYMAKER', 'INSIDE_FORWARD'],
  ATACANTE: ['POACHER', 'TARGET_FORWARD', 'ADVANCED_FORWARD', 'FALSE_9'],
}

// Hand-transcribed straight from the backend `RoleArchetype.defaultFor` switch.
const EXPECTED_DEFAULT_ARCHETYPE: Record<DetailedPosition, RoleArchetype> = {
  GOLEIRO: 'GOALKEEPER',
  ZAGUEIRO: 'DEFENDER',
  LATERAL_DIREITO: 'FULLBACK',
  LATERAL_ESQUERDO: 'FULLBACK',
  VOLANTE: 'HOLDING',
  MEIA_CENTRAL: 'BOX_TO_BOX',
  MEIA_ARMADOR: 'PLAYMAKER',
  MEIA_DIREITA: 'WINGER',
  MEIA_ESQUERDA: 'WINGER',
  PONTA_DIREITA: 'WINGER',
  PONTA_ESQUERDA: 'WINGER',
  ATACANTE: 'ADVANCED_FORWARD',
}

describe('ARCHETYPE_LABEL / FOCUS_LABEL', () => {
  it('has a PT-BR label for every RoleArchetype', () => {
    expect(new Set(Object.keys(ARCHETYPE_LABEL))).toEqual(new Set(ALL_ARCHETYPES))
    for (const a of ALL_ARCHETYPES) expect(ARCHETYPE_LABEL[a].length).toBeGreaterThan(0)
  })

  it('has a PT-BR label for every RoleFocus', () => {
    expect(new Set(Object.keys(FOCUS_LABEL))).toEqual(new Set(ALL_FOCUSES))
    for (const f of ALL_FOCUSES) expect(FOCUS_LABEL[f].length).toBeGreaterThan(0)
  })
})

describe('ARCHETYPES_FOR_POSITION (sync-guard against the backend MATRIX)', () => {
  it.each(ALL_POSITIONS)('%s lists exactly the backend-valid archetypes, in enum order', position => {
    expect(ARCHETYPES_FOR_POSITION[position]).toEqual(EXPECTED_ARCHETYPES_FOR_POSITION[position])
  })
})

describe('FOCUSES_FOR (sync-guard against the backend MATRIX, order matters)', () => {
  it.each(
    ALL_ARCHETYPES.flatMap(archetype =>
      (Object.keys(EXPECTED_MATRIX[archetype]) as DetailedPosition[]).map(position => [archetype, position] as const),
    ),
  )('%s at %s exposes the exact backend focus list, default first', (archetype, position) => {
    expect(FOCUSES_FOR(archetype, position)).toEqual(EXPECTED_MATRIX[archetype][position])
  })

  it('falls back to the archetype canonical default focus for a pair the MATRIX does not cover', () => {
    // WINGER is not valid for ZAGUEIRO — focusesFor still returns a single-element list.
    const result = FOCUSES_FOR('WINGER', 'ZAGUEIRO')
    expect(result).toHaveLength(1)
    expect(result[0]).toBe('BALANCED')
  })
})

describe('defaultArchetypeFor (sync-guard against the backend RoleArchetype.defaultFor)', () => {
  it.each(ALL_POSITIONS)('%s defaults to the expected archetype', position => {
    expect(defaultArchetypeFor(position)).toBe(EXPECTED_DEFAULT_ARCHETYPE[position])
  })
})
