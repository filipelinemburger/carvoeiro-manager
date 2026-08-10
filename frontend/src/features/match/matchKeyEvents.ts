import type { LiveEvent } from '../../api/types'

export type ScorerLine = { name: string; minutes: number[] }
export type DisciplineLine = { name: string; minute: number; kind: 'YELLOW' | 'RED' | 'INJURY' | 'PENALTY_MISSED' }
export type SideKeyEvents = { scorers: ScorerLine[]; discipline: DisciplineLine[] }
export type KeyEvents = { home: SideKeyEvents; away: SideKeyEvents }

const GOAL_TYPES = new Set<LiveEvent['type']>([
  'GOAL', 'SET_PIECE_GOAL', 'PENALTY_GOAL', 'FREE_KICK_GOAL', 'CORNER_GOAL',
])

const DISCIPLINE_KIND: Partial<Record<LiveEvent['type'], DisciplineLine['kind']>> = {
  YELLOW_CARD: 'YELLOW',
  RED_CARD: 'RED',
  INJURY: 'INJURY',
  PENALTY_MISSED: 'PENALTY_MISSED',
}

/**
 * Summarises the raw event stream into the Championship-Manager-style key-events block: goals
 * grouped by scorer (first scorer first, minutes chronological) and discipline lines in
 * chronological order. CHANCE/SUBSTITUTION/TACTICS_CHANGE/HALF_TIME are not key events and are
 * dropped.
 *
 * Two nuances the simulation engine forces, both covered by dedicated tests:
 * - `CORNER_GOAL` carries the corner *taker* in `playerInName` and the *scorer* in `playerName`;
 *   goals are always keyed on `playerName`.
 * - A second yellow makes the engine emit BOTH a `YELLOW_CARD` and a `RED_CARD` event for the
 *   same player/minute; both discipline lines are kept (the DTO carries no flag to collapse them).
 *
 * Own goals do not exist in this engine, so `e.home` is always the scoring side — there is no
 * inversion to handle.
 */
export function summarizeKeyEvents(events: LiveEvent[]): KeyEvents {
  // Insertion-ordered maps so the first scorer of each side is listed first, CM-style.
  const homeScorers = new Map<string, number[]>()
  const awayScorers = new Map<string, number[]>()
  const homeDiscipline: DisciplineLine[] = []
  const awayDiscipline: DisciplineLine[] = []

  for (const e of events) {
    const scorers = e.home ? homeScorers : awayScorers
    const discipline = e.home ? homeDiscipline : awayDiscipline

    if (GOAL_TYPES.has(e.type)) {
      const minutes = scorers.get(e.playerName)
      if (minutes) minutes.push(e.minute)
      else scorers.set(e.playerName, [e.minute])
      continue
    }

    const kind = DISCIPLINE_KIND[e.type]
    if (kind) discipline.push({ name: e.playerName, minute: e.minute, kind })
  }

  const toScorerLines = (scorers: Map<string, number[]>): ScorerLine[] =>
    [...scorers].map(([name, minutes]) => ({ name, minutes }))

  return {
    home: { scorers: toScorerLines(homeScorers), discipline: homeDiscipline },
    away: { scorers: toScorerLines(awayScorers), discipline: awayDiscipline },
  }
}
