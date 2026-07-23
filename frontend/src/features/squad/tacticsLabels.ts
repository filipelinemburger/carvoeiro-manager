import type { Mentality, GameStyle, Pressing, Marking } from '../../api/types'

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

export const GAME_STYLE_LABEL: Record<GameStyle, string> = {
  EQUILIBRADO: 'Equilibrado',
  JOGO_DE_PASSES: 'Jogo de passes',
  CONTRA_ATAQUE: 'Contra-ataque',
  JOGO_DIRETO: 'Jogo direto',
  PRESSAO_ALTA: 'Pressão alta',
  RETRANCA: 'Retranca',
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
