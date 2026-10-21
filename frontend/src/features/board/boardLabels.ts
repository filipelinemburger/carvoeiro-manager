import type {
  AdvanceResponse, BoardChange, BoardNewsType, BoardChangeReason, BoardObjective, BoardOutcome, BoardProgress, BoardStage,
} from '../../api/types'

// Every board word lives here: the backend sends enums and numbers only.

/** The board's mood for a confidence value (0–100). */
export function moodLabel(confidence: number): string {
  if (confidence >= 80) return 'Muito satisfeita'
  if (confidence >= 60) return 'Satisfeita'
  if (confidence >= 45) return 'Neutra'
  if (confidence >= 30) return 'Preocupada'
  if (confidence >= 15) return 'Insatisfeita'
  return 'Cargo por um fio'
}

/** Bar colour: warning below the ultimatum-lift line, error at the ultimatum line and below. */
export function moodColor(confidence: number): 'success' | 'warning' | 'error' {
  if (confidence <= 30) return 'error'
  if (confidence < 45) return 'warning'
  return 'success'
}

/** A signed number with a real minus sign (U+2212), as elsewhere in the UI. */
export function signed(n: number): string {
  if (n > 0) return `+${n}`
  if (n < 0) return `−${Math.abs(n)}`
  return '0'
}

export const STAGE_LABEL: Record<BoardStage, string> = {
  NOT_QUALIFIED: 'primeira fase',
  QUARTERFINAL: 'quartas de final',
  SEMIFINAL: 'semifinais',
  FINAL: 'final',
  CHAMPION: 'título',
}

export const PROGRESS_LABEL: Record<BoardProgress, string> = {
  ON_TRACK: 'no caminho',
  AT_RISK: 'em risco',
  OFF_TRACK: 'fora da meta',
}

export const OUTCOME_LABEL: Record<Exclude<BoardOutcome, 'PENDING'>, string> = {
  MET: 'cumprido',
  FAILED: 'não cumprido',
}

/** Status chip text and colour: the outcome once resolved, else live progress, else nothing. */
export function objectiveChip(o: BoardObjective): { label: string; color: 'success' | 'warning' | 'error' | 'default' } | null {
  if (o.outcome === 'MET') return { label: OUTCOME_LABEL.MET, color: 'success' }
  if (o.outcome === 'FAILED') return { label: OUTCOME_LABEL.FAILED, color: 'error' }
  if (o.progress === 'ON_TRACK') return { label: PROGRESS_LABEL.ON_TRACK, color: 'success' }
  if (o.progress === 'AT_RISK') return { label: PROGRESS_LABEL.AT_RISK, color: 'warning' }
  if (o.progress === 'OFF_TRACK') return { label: PROGRESS_LABEL.OFF_TRACK, color: 'error' }
  return null
}

function leaguePositionText(tier: number | null, position: number): string {
  if (position === 4) return 'Terminar no G-4'
  if (position === 16 && tier !== 3) return 'Terminar fora do Z-4'
  if (tier === 1 && position === 6) return 'Terminar no G-6'
  if (tier === 2 && position === 10) return 'Terminar na primeira metade da tabela'
  if (tier === 3) return `Terminar a primeira fase entre os ${position} primeiros`
  return `Terminar entre os ${position} primeiros`
}

function stageText(stage: BoardStage, competition: string): string {
  switch (stage) {
    case 'CHAMPION': return `Ser campeão — ${competition}`
    case 'FINAL': return `Chegar à final — ${competition}`
    case 'SEMIFINAL': return `Chegar às semifinais — ${competition}`
    case 'QUARTERFINAL': return `Classificar para as quartas de final — ${competition}`
    default: return competition
  }
}

/**
 * The objective's text. Competition names come from data and are used without articles
 * ("Ser campeão — Campeonato Catarinense"): nothing says whether a name takes "do" or "da".
 */
export function objectiveText(o: BoardObjective): string {
  const competition = o.competitionName ?? ''
  if (o.kind === 'FINANCE') return 'Terminar a temporada com saldo positivo'
  if (o.targetStage) return stageText(o.targetStage, competition)
  if (o.targetPosition !== null) return leaguePositionText(o.tier, o.targetPosition)
  return competition
}

/** Live detail under an objective, e.g. "atualmente 11º". */
export function objectiveDetail(o: BoardObjective): string | null {
  return o.outcome === 'PENDING' && o.currentPosition !== null ? `atualmente ${o.currentPosition}º` : null
}

const REASON_LABEL: Record<BoardChangeReason, string> = {
  MATCH_RESULT: 'Resultado',
  HEAVY_DEFEAT: 'Goleada sofrida',
  TABLE_POSITION: 'Posição na tabela',
  NEGATIVE_BALANCE: 'Saldo negativo',
  STATE_MET: 'Meta cumprida',
  STATE_FAILED: 'Meta não cumprida',
  TITLE_WON: 'Título conquistado',
  LEAGUE_MET: 'Meta cumprida',
  LEAGUE_FAILED: 'Meta não cumprida',
  FINANCE_MET: 'Meta cumprida',
  FINANCE_FAILED: 'Meta não cumprida',
  NEW_SEASON: 'Nova temporada',
  SACKING_AVERTED: 'Demissão evitada',
}

/** One change line's text, without the delta: "Derrota para Avaí (0–2)". */
export function changeText(change: BoardChange): string {
  const p = change.payload ?? {}
  if (change.reason === 'MATCH_RESULT' && typeof p.opponent === 'string' && typeof p.score === 'string') {
    const [gf, ga] = p.score.split('–').map(Number)
    const verb = gf > ga ? 'Vitória sobre' : gf === ga ? 'Empate com' : 'Derrota para'
    return `${verb} ${p.opponent} (${p.score})`
  }
  if (change.reason === 'TABLE_POSITION' && typeof p.position === 'number') {
    return `Posição na tabela (${p.position}º)`
  }
  return REASON_LABEL[change.reason] ?? change.reason
}

export const BOARD_NEWS_TITLE: Record<BoardNewsType, string> = {
  BOARD_OBJECTIVES: 'Metas da diretoria',
  BOARD_ULTIMATUM: 'Ultimato da diretoria',
  BOARD_ULTIMATUM_LIFTED: 'Diretoria mais tranquila',
  BOARD_REVIEW: 'Avaliação da temporada',
  BOARD_SACKED: 'Você foi demitido',
  BOARD_SACKING_AVERTED: 'A diretoria queria sua saída',
}

export function isBoardNews(type: AdvanceResponse['type']): type is BoardNewsType {
  return type in BOARD_NEWS_TITLE
}
