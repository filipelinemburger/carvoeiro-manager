import type { Mentality, Pressing, Marking } from '../../api/types'

/**
 * Friendly Portuguese display names for the tactic enum values. Single source
 * for the squad TacticsPanel and the in-match MatchActionsDialog — the wire
 * format stays the enum; these are display-only.
 */
export const MENTALITY_LABEL: Record<Mentality, string> = {
  DEFENSIVE: 'Defensiva',
  BALANCED: 'Equilibrada',
  ATTACKING: 'Ofensiva',
}

export const PRESSING_LABEL: Record<Pressing, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
}

export const MARKING_LABEL: Record<Marking, string> = {
  ZONAL: 'Por zona',
  MAN_TIGHT: 'Individual',
}
