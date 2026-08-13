import type { DetailedPosition, PlayerResponse } from '../../api/types'

/** Full PT-BR label for each detailed position (matches the backend `DetailedPosition.label()`). */
export const DETAILED_POSITION_LABEL: Record<DetailedPosition, string> = {
  GOLEIRO: 'Goleiro',
  ZAGUEIRO: 'Zagueiro',
  LATERAL_DIREITO: 'Lateral direito',
  LATERAL_ESQUERDO: 'Lateral esquerdo',
  VOLANTE: 'Volante',
  MEIA_CENTRAL: 'Meia central',
  MEIA_ARMADOR: 'Meia armador',
  MEIA_DIREITA: 'Meia direita',
  MEIA_ESQUERDA: 'Meia esquerda',
  PONTA_DIREITA: 'Ponta direita',
  PONTA_ESQUERDA: 'Ponta esquerda',
  ATACANTE: 'Atacante',
}

/** Short abbreviation for each detailed position (matches the backend `DetailedPosition.shortLabel()`). */
export const DETAILED_POSITION_SHORT: Record<DetailedPosition, string> = {
  GOLEIRO: 'GOL',
  ZAGUEIRO: 'ZAG',
  LATERAL_DIREITO: 'LD',
  LATERAL_ESQUERDO: 'LE',
  VOLANTE: 'VOL',
  MEIA_CENTRAL: 'MC',
  MEIA_ARMADOR: 'MEI',
  MEIA_DIREITA: 'MD',
  MEIA_ESQUERDA: 'ME',
  PONTA_DIREITA: 'PD',
  PONTA_ESQUERDA: 'PE',
  ATACANTE: 'ATA',
}

/** The coarse band each detailed position belongs to (mirrors `DetailedPosition.coarse()`). */
export const DETAILED_TO_COARSE: Record<DetailedPosition, 'GK' | 'DEF' | 'MID' | 'ATT'> = {
  GOLEIRO: 'GK',
  ZAGUEIRO: 'DEF',
  LATERAL_DIREITO: 'DEF',
  LATERAL_ESQUERDO: 'DEF',
  VOLANTE: 'MID',
  MEIA_CENTRAL: 'MID',
  MEIA_ARMADOR: 'MID',
  MEIA_DIREITA: 'MID',
  MEIA_ESQUERDA: 'MID',
  PONTA_DIREITA: 'ATT',
  PONTA_ESQUERDA: 'ATT',
  ATACANTE: 'ATT',
}

type WithPosition = { position: string; detailedPosition?: DetailedPosition | null }

/** The detailed full label when present, else the coarse label (legacy/fallback). */
export function positionLabel(p: WithPosition, coarseLabel: Record<string, string>): string {
  return p.detailedPosition ? DETAILED_POSITION_LABEL[p.detailedPosition] : (coarseLabel[p.position] ?? p.position)
}

/** The detailed short abbreviation when present, else the coarse abbreviation (legacy/fallback). */
export function positionShort(p: WithPosition, coarseShort: Record<string, string>): string {
  return p.detailedPosition ? DETAILED_POSITION_SHORT[p.detailedPosition] : (coarseShort[p.position] ?? p.position)
}

/** Coarse fallbacks for a player with no detailed position (legacy payloads). */
export const COARSE_POS_SHORT: Record<PlayerResponse['position'], string> = {
  GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA',
}

/** Short position abbr for a squad-table cell: detailed when present, else coarse. */
export function squadPositionLabel(player: PlayerResponse): string {
  return positionShort(player, COARSE_POS_SHORT)
}

/**
 * Unordered adjacent (1-step) pairs of the position graph — mirrors the backend
 * `DetailedPosition.ADJACENT` set (spec §4.1) exactly.
 */
const ADJACENT_PAIRS: ReadonlyArray<readonly [DetailedPosition, DetailedPosition]> = [
  ['LATERAL_DIREITO', 'MEIA_DIREITA'], ['LATERAL_ESQUERDO', 'MEIA_ESQUERDA'],
  ['MEIA_DIREITA', 'PONTA_DIREITA'], ['MEIA_ESQUERDA', 'PONTA_ESQUERDA'],
  ['VOLANTE', 'MEIA_CENTRAL'], ['MEIA_CENTRAL', 'MEIA_ARMADOR'],
  ['MEIA_ARMADOR', 'ATACANTE'], ['ZAGUEIRO', 'VOLANTE'],
  ['LATERAL_DIREITO', 'ZAGUEIRO'], ['LATERAL_ESQUERDO', 'ZAGUEIRO'],
  ['PONTA_DIREITA', 'ATACANTE'], ['PONTA_ESQUERDA', 'ATACANTE'],
]

/**
 * Tactical distance between two detailed positions: 0 = same, 1 = adjacent (natural
 * improvisation), 2 = far, 3 = goalkeeper vs any outfield position (either direction). Mirrors
 * the backend `DetailedPosition.distance()` exactly.
 */
export function positionDistance(a: DetailedPosition, b: DetailedPosition): number {
  if (a === b) return 0
  if (a === 'GOLEIRO' || b === 'GOLEIRO') return 3
  const adjacent = ADJACENT_PAIRS.some(([x, y]) => (x === a && y === b) || (x === b && y === a))
  return adjacent ? 1 : 2
}

/** Anything carrying the two-axis position payload — the shape every helper below reads. */
export type WithPositions = {
  detailedPosition?: DetailedPosition | null
  altPositions?: DetailedPosition[] | null
}

/**
 * Every position a player counts as natural in — primary first, then alternates, deduplicated.
 * Mirrors the backend `Player.positions()`. The single definition of "the player's positions":
 * `isNatural`, the slot assigner and every label below read it, so they cannot drift apart.
 */
export function playerPositions(p: WithPositions): DetailedPosition[] {
  const mine = [p.detailedPosition, ...(p.altPositions ?? [])].filter(
    (x): x is DetailedPosition => x != null,
  )
  return Array.from(new Set(mine))
}

/** The player's positions as short labels ("ZAG · LD · VOL"); null when they have none. */
export function positionsShortLabel(p: WithPositions): string | null {
  const positions = playerPositions(p)
  return positions.length === 0 ? null : positions.map(pos => DETAILED_POSITION_SHORT[pos]).join(' · ')
}

/**
 * Whether a player is NATURAL for a slot's accepted positions: any of the player's positions
 * (primary `detailedPosition` or `altPositions`) is in `accepted`. Mirrors the backend
 * `SlotAssigner.isNatural` exactly.
 */
export function isNatural(p: WithPositions, accepted: DetailedPosition[]): boolean {
  return playerPositions(p).some(pos => accepted.includes(pos))
}
