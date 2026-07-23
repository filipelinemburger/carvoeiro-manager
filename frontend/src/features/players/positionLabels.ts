import type { DetailedPosition } from '../../api/types'

/** Full PT-BR label for each detailed position (matches the backend `DetailedPosition.label()`). */
export const DETAILED_POSITION_LABEL: Record<DetailedPosition, string> = {
  GOLEIRO: 'Goleiro',
  ZAGUEIRO: 'Zagueiro',
  LATERAL_ESQUERDO: 'Lateral esquerdo',
  LATERAL_DIREITO: 'Lateral direito',
  VOLANTE: 'Volante',
  MEIA_ARMADOR: 'Meia armador',
  PONTA: 'Ponta',
  FALSO_9: 'Falso 9',
  ATACANTE: 'Atacante',
}

/** Short abbreviation for each detailed position (matches the backend `DetailedPosition.shortLabel()`). */
export const DETAILED_POSITION_SHORT: Record<DetailedPosition, string> = {
  GOLEIRO: 'GOL',
  ZAGUEIRO: 'ZAG',
  LATERAL_ESQUERDO: 'LE',
  LATERAL_DIREITO: 'LD',
  VOLANTE: 'VOL',
  MEIA_ARMADOR: 'MEI',
  PONTA: 'PON',
  FALSO_9: 'F9',
  ATACANTE: 'ATA',
}

/** The coarse band each detailed position belongs to (mirrors `DetailedPosition.coarse()`). */
export const DETAILED_TO_COARSE: Record<DetailedPosition, 'GK' | 'DEF' | 'MID' | 'ATT'> = {
  GOLEIRO: 'GK',
  ZAGUEIRO: 'DEF',
  LATERAL_ESQUERDO: 'DEF',
  LATERAL_DIREITO: 'DEF',
  VOLANTE: 'MID',
  MEIA_ARMADOR: 'MID',
  PONTA: 'ATT',
  FALSO_9: 'ATT',
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
