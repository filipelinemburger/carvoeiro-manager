import type { DetailedPosition, RoleArchetype, RoleFocus } from '../../api/types'

/** PT-BR label for each role archetype (matches the backend `RoleArchetype.label()` exactly). */
export const ARCHETYPE_LABEL: Record<RoleArchetype, string> = {
  GOALKEEPER: 'Goleiro',
  STOPPER: 'Xerife',
  FULLBACK: 'Lateral',
  BALL_PLAYING_DEFENDER: 'Zagueiro construtor',
  HOLDING: 'Primeiro volante',
  BOX_TO_BOX: 'Box-to-box',
  PLAYMAKER: 'Armador',
  POACHER: 'Oportunista',
  TARGET_FORWARD: 'Pivô',
  WINGER: 'Ponta',
  SWEEPER_KEEPER: 'Goleiro-líbero',
  BALL_PLAYING_KEEPER: 'Goleiro construtor',
  WINGBACK: 'Ala',
  FALSEBACK: 'Falso lateral',
  ATTACKING_WINGBACK: 'Ala ofensivo',
  INVERTED_WINGBACK: 'Ala invertido',
  DEFENDER: 'Zagueiro',
  WIDE_BACK: 'Zagueiro ala',
  DEEP_LYING_PLAYMAKER: 'Volante armador',
  CENTRE_HALF: 'Terceiro zagueiro',
  WIDE_HALF: 'Volante ala',
  BOX_CRASHER: 'Volante de chegada',
  HALF_WINGER: 'Meia-ala',
  SHADOW_STRIKER: 'Segundo atacante',
  CLASSIC_10: 'Camisa 10',
  WIDE_PLAYMAKER: 'Armador aberto',
  WIDE_MIDFIELDER: 'Meia de lado',
  INSIDE_FORWARD: 'Atacante por dentro',
  ADVANCED_FORWARD: 'Atacante móvel',
  FALSE_9: 'Falso 9',
}

/** PT-BR label for each role focus (matches the backend `RoleFocus.label()` exactly). */
export const FOCUS_LABEL: Record<RoleFocus, string> = {
  DEFEND: 'Defesa',
  BALANCED: 'Equilibrado',
  ATTACK: 'Ataque',
  BUILD_UP: 'Construção',
  SUPPORT: 'Apoio',
  ROAMING: 'Livre',
  VERSATILE: 'Versátil',
  AGGRESSIVE: 'Agressivo',
  BALL_WINNING: 'Combativo',
  WIDE: 'Aberto',
}

/**
 * Each archetype's canonical default focus (the backend constructor's second argument) — used as
 * the fallback focus list for an archetype/position pair the MATRIX doesn't cover, mirroring the
 * backend `RoleArchetype.focusesFor`'s `getOrDefault(position, List.of(canonicalDefault))`.
 */
const CANONICAL_DEFAULT_FOCUS: Record<RoleArchetype, RoleFocus> = {
  GOALKEEPER: 'DEFEND',
  STOPPER: 'BALANCED',
  FULLBACK: 'BALANCED',
  BALL_PLAYING_DEFENDER: 'DEFEND',
  HOLDING: 'DEFEND',
  BOX_TO_BOX: 'BALANCED',
  PLAYMAKER: 'BALANCED',
  POACHER: 'ATTACK',
  TARGET_FORWARD: 'ATTACK',
  WINGER: 'BALANCED',
  SWEEPER_KEEPER: 'BALANCED',
  BALL_PLAYING_KEEPER: 'BUILD_UP',
  WINGBACK: 'BALANCED',
  FALSEBACK: 'BALANCED',
  ATTACKING_WINGBACK: 'SUPPORT',
  INVERTED_WINGBACK: 'BUILD_UP',
  DEFENDER: 'DEFEND',
  WIDE_BACK: 'DEFEND',
  DEEP_LYING_PLAYMAKER: 'DEFEND',
  CENTRE_HALF: 'DEFEND',
  WIDE_HALF: 'DEFEND',
  BOX_CRASHER: 'BALANCED',
  HALF_WINGER: 'BALANCED',
  SHADOW_STRIKER: 'ATTACK',
  CLASSIC_10: 'ATTACK',
  WIDE_PLAYMAKER: 'ATTACK',
  WIDE_MIDFIELDER: 'SUPPORT',
  INSIDE_FORWARD: 'BALANCED',
  ADVANCED_FORWARD: 'ATTACK',
  FALSE_9: 'BUILD_UP',
}

/**
 * archetype → (valid position → focuses, default first). A hand-mirrored copy of the backend
 * `RoleArchetype.MATRIX` (futbin.com/26/roles); kept honest by `roleLabels.test.ts`'s
 * independently-transcribed sync-guard, the frontend analogue of `RoleArchetypeFutbinSyncTest`.
 */
const MATRIX: Record<RoleArchetype, Partial<Record<DetailedPosition, RoleFocus[]>>> = {
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

// RoleArchetype enum declaration order (backend source of truth) — NOT the MATRIX object's own
// key order above (which follows the backend's `Map.ofEntries` grouping by position, a different
// order), so ARCHETYPES_FOR_POSITION below reflects true enum order per position.
const ALL_ARCHETYPES: RoleArchetype[] = [
  'GOALKEEPER', 'STOPPER', 'FULLBACK', 'BALL_PLAYING_DEFENDER', 'HOLDING', 'BOX_TO_BOX', 'PLAYMAKER',
  'POACHER', 'TARGET_FORWARD', 'WINGER', 'SWEEPER_KEEPER', 'BALL_PLAYING_KEEPER', 'WINGBACK',
  'FALSEBACK', 'ATTACKING_WINGBACK', 'INVERTED_WINGBACK', 'DEFENDER', 'WIDE_BACK',
  'DEEP_LYING_PLAYMAKER', 'CENTRE_HALF', 'WIDE_HALF', 'BOX_CRASHER', 'HALF_WINGER',
  'SHADOW_STRIKER', 'CLASSIC_10', 'WIDE_PLAYMAKER', 'WIDE_MIDFIELDER', 'INSIDE_FORWARD',
  'ADVANCED_FORWARD', 'FALSE_9',
]
const ALL_POSITIONS: DetailedPosition[] = [
  'GOLEIRO', 'ZAGUEIRO', 'LATERAL_DIREITO', 'LATERAL_ESQUERDO', 'VOLANTE', 'MEIA_CENTRAL',
  'MEIA_ARMADOR', 'MEIA_DIREITA', 'MEIA_ESQUERDA', 'PONTA_DIREITA', 'PONTA_ESQUERDA', 'ATACANTE',
]

/** position → the archetypes valid for it, in `RoleArchetype` enum declaration order. */
export const ARCHETYPES_FOR_POSITION: Record<DetailedPosition, RoleArchetype[]> = (() => {
  const out = {} as Record<DetailedPosition, RoleArchetype[]>
  for (const pos of ALL_POSITIONS) out[pos] = []
  for (const archetype of ALL_ARCHETYPES) {
    for (const pos of Object.keys(MATRIX[archetype]) as DetailedPosition[]) {
      out[pos].push(archetype)
    }
  }
  return out
})()

/**
 * The valid focuses for an archetype at a given position, default first. Mirrors the backend
 * `RoleArchetype.focusesFor` exactly, including its canonical-default fallback for a pair the
 * MATRIX doesn't cover.
 */
export function FOCUSES_FOR(archetype: RoleArchetype, position: DetailedPosition): RoleFocus[] {
  return MATRIX[archetype][position] ?? [CANONICAL_DEFAULT_FOCUS[archetype]]
}

/**
 * The vanilla archetype a position falls back to when nothing is persisted/familiar. Mirrors the
 * backend `RoleArchetype.defaultFor` switch exactly.
 */
export function defaultArchetypeFor(position: DetailedPosition): RoleArchetype {
  switch (position) {
    case 'GOLEIRO': return 'GOALKEEPER'
    case 'ZAGUEIRO': return 'DEFENDER'
    case 'LATERAL_DIREITO':
    case 'LATERAL_ESQUERDO': return 'FULLBACK'
    case 'VOLANTE': return 'HOLDING'
    case 'MEIA_CENTRAL': return 'BOX_TO_BOX'
    case 'MEIA_ARMADOR': return 'PLAYMAKER'
    case 'MEIA_DIREITA':
    case 'MEIA_ESQUERDA':
    case 'PONTA_DIREITA':
    case 'PONTA_ESQUERDA': return 'WINGER'
    case 'ATACANTE': return 'ADVANCED_FORWARD'
  }
}
