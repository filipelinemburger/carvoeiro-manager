import type { LiveEvent, LivePlayer } from '../../api/types'

export type Booking = 'YELLOW' | 'RED' | null

/**
 * The position band order used to sort players when no specific reference is available.
 * Exported so both branches of rankByFit share one source.
 */
export const POSITION_ORDER = ['GK', 'DEF', 'MID', 'ATT'] as const

/**
 * Computes the number of minutes a player has spent on the pitch based on the live event stream.
 *
 * Entry minute: the minute of the first SUBSTITUTION event where playerInId === playerId,
 * or 0 when there is none (the player started the match).
 *
 * Exit minute: the minute of the first SUBSTITUTION event at or after the entry minute
 * where playerId === playerId (the player went off), or currentMinute if there is none
 * (the player is still on).
 *
 * Result is Math.max(0, exit - entry).
 *
 * Note: Entry and exit are resolved as two independent "first match" searches over the
 * entire event array. The implementation does not assume events are in chronological order.
 */
export function minutesPlayed(playerId: string, events: LiveEvent[], currentMinute: number): number {
  // First scan: find entry minute (first SUBSTITUTION where playerInId === playerId, or 0)
  let entryMinute = 0
  for (const event of events) {
    if (event.type === 'SUBSTITUTION' && event.playerInId === playerId) {
      entryMinute = event.minute
      break
    }
  }

  // Second scan: find exit minute (first SUBSTITUTION at or after entry where playerId === playerId)
  let exitMinute = currentMinute
  for (const event of events) {
    if (event.type === 'SUBSTITUTION' && event.playerId === playerId && event.minute >= entryMinute) {
      exitMinute = event.minute
      break
    }
  }

  return Math.max(0, exitMinute - entryMinute)
}

/**
 * Determines the disciplinary status of a player based on the live event stream.
 *
 * Returns:
 * - 'RED' when any RED_CARD event carries this playerId
 * - 'YELLOW' when any YELLOW_CARD event carries this playerId (and no RED_CARD)
 * - null otherwise
 *
 * Note: when a player receives a second yellow, the engine emits BOTH a YELLOW_CARD
 * and a RED_CARD event at the same minute; this function prioritizes RED.
 */
export function bookingOf(playerId: string, events: LiveEvent[]): Booking {
  let hasYellow = false

  for (const event of events) {
    if (event.playerId === playerId) {
      if (event.type === 'RED_CARD') {
        return 'RED'
      }
      if (event.type === 'YELLOW_CARD') {
        hasYellow = true
      }
    }
  }

  return hasYellow ? 'YELLOW' : null
}

/**
 * Ranks a list of candidate players by suitability to replace a reference player.
 *
 * Returns a new array sorted by, in order:
 * 1. Same coarse position as reference (0 when equal, 1 otherwise)
 * 2. Same role as reference (0 when equal, 1 otherwise)
 * 3. Overall rating descending
 * 4. Player ID ascending (localeCompare), ensuring a total and stable order
 *
 * When reference is null, falls back to the dialog's existing default order:
 * by position band (GK < DEF < MID < ATT), then overall descending, then playerId ascending.
 *
 * The input array is never mutated.
 */
export function rankByFit(candidates: LivePlayer[], reference: LivePlayer | null): LivePlayer[] {
  const sorted = [...candidates]

  if (reference === null) {
    // Null reference: position band order, then overall descending, then playerId ascending
    sorted.sort((a, b) => {
      const positionA = POSITION_ORDER.indexOf(a.position as typeof POSITION_ORDER[number])
      const positionB = POSITION_ORDER.indexOf(b.position as typeof POSITION_ORDER[number])

      if (positionA !== positionB) {
        return positionA - positionB
      }

      if (a.overall !== b.overall) {
        return b.overall - a.overall
      }

      return a.playerId.localeCompare(b.playerId)
    })
  } else {
    // Reference provided: same position, then same role, then overall, then playerId
    sorted.sort((a, b) => {
      // 1. Same position (0 when equal, 1 otherwise)
      const samePositionA = a.position === reference.position ? 0 : 1
      const samePositionB = b.position === reference.position ? 0 : 1

      if (samePositionA !== samePositionB) {
        return samePositionA - samePositionB
      }

      // 2. Same role (0 when equal, 1 otherwise)
      const sameRoleA = a.role === reference.role ? 0 : 1
      const sameRoleB = b.role === reference.role ? 0 : 1

      if (sameRoleA !== sameRoleB) {
        return sameRoleA - sameRoleB
      }

      // 3. Overall descending
      if (a.overall !== b.overall) {
        return b.overall - a.overall
      }

      // 4. Player ID ascending (localeCompare)
      return a.playerId.localeCompare(b.playerId)
    })
  }

  return sorted
}

/**
 * Determines whether a candidate player is a natural suggestion to replace a reference player.
 *
 * Returns true only when reference is non-null and the candidate has the same coarse position.
 *
 * Note: this check is deliberately coarse because the live state carries no detailed position
 * (LATERAL_ESQUERDO etc.). Suggestions are necessarily approximate.
 */
export function isSuggestedFor(candidate: LivePlayer, reference: LivePlayer | null): boolean {
  if (reference === null) {
    return false
  }

  return candidate.position === reference.position
}
