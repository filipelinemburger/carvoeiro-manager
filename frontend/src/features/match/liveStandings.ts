import type { StandingRow } from '../../api/types'

/** One fixture's current score, already joined to real club ids on both sides. */
export type LiveResult = {
  homeClubId: string
  awayClubId: string
  homeGoals: number
  awayGoals: number
}

/**
 * Mirrors `StandingsCalculator`'s ordering EXACTLY (backend
 * `competition/StandingsCalculator.java:61-65`): points desc → goal difference desc → goals for
 * desc → club name asc. Any divergence would make the table visibly jump the instant the round
 * completes and the server's own table takes over.
 *
 * The last tie-break is `clubName`, NOT the `commonName` the table renders — and it is a plain
 * code-unit comparison rather than `localeCompare`, because Java's `String::compareTo` (what the
 * backend sorts with) is code-point ordered too. `localeCompare` would order accented names
 * differently and reintroduce the jump this comparator exists to prevent.
 */
function compareRows(a: StandingRow, b: StandingRow): number {
  if (a.points !== b.points) return b.points - a.points
  if (a.goalDifference !== b.goalDifference) return b.goalDifference - a.goalDifference
  if (a.goalsFor !== b.goalsFor) return b.goalsFor - a.goalsFor
  if (a.clubName === b.clubName) return 0
  return a.clubName < b.clubName ? -1 : 1
}

/** Adds one side of a result to a copied row. Callers pass the goals already oriented. */
function withResult(row: StandingRow, scored: number, conceded: number): StandingRow {
  const goalsFor = row.goalsFor + scored
  const goalsAgainst = row.goalsAgainst + conceded
  return {
    ...row,
    played: row.played + 1,
    won: row.won + (scored > conceded ? 1 : 0),
    drawn: row.drawn + (scored === conceded ? 1 : 0),
    lost: row.lost + (scored < conceded ? 1 : 0),
    goalsFor,
    goalsAgainst,
    goalDifference: goalsFor - goalsAgainst,
    points: row.points + (scored > conceded ? 3 : scored === conceded ? 1 : 0),
  }
}

/**
 * The live league table: the pre-round standings the server returned, plus every result of the
 * round currently being played (the user's own match and every parallel fixture alike — the
 * current match is not special-cased).
 *
 * `getStandings` is safe as a base precisely because none of the round is persisted yet:
 * `LiveMatchService.kickoff` only stores the other fixtures' scores as JSON on the `live_match`
 * row, and `finish()` is the first thing to write any of them back to `fixture`. So nothing here
 * is double-counted.
 *
 * A result naming a club absent from `base` is SKIPPED WHOLE, not invented — a cup fixture or a
 * club from another division can produce one, and adding a row would show a club in a table it
 * does not belong to. Dropping the entire result (rather than only the unknown side) is what the
 * backend does: `StandingsCalculator.java:35-37` `continue`s the fixture when either club is
 * missing from the table, so a half-counted match would desync the two tables.
 *
 * Pure: `base` is React Query cache data, so neither the array nor any row is mutated.
 */
export function liveStandings(base: StandingRow[], results: LiveResult[]): StandingRow[] {
  const byClubId = new Map<string, StandingRow>(base.map(row => [row.clubId, row]))

  for (const result of results) {
    const home = byClubId.get(result.homeClubId)
    const away = byClubId.get(result.awayClubId)
    if (!home || !away) continue

    byClubId.set(result.homeClubId, withResult(home, result.homeGoals, result.awayGoals))
    byClubId.set(result.awayClubId, withResult(away, result.awayGoals, result.homeGoals))
  }

  return [...byClubId.values()].sort(compareRows)
}
