import { pitchSlots } from './pitchSlots'
import type { DetailedPosition } from '../../api/types'
import { isNatural, positionDistance } from '../players/positionLabels'

export type AssignablePlayer = {
  id: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  detailedPosition?: DetailedPosition | null
  /** 0–2 alternate detailed positions; any of them (like the primary) counts as NATURAL for a slot. */
  altPositions?: DetailedPosition[]
  overall: number
}

// Representative detailed position per coarse band, used only when a player has neither a
// primary detailedPosition nor any altPositions (legacy payloads) so distance-fallback still has
// something to measure against instead of leaving the player unplaceable.
const BAND_FALLBACK_POSITION: Record<AssignablePlayer['position'], DetailedPosition> = {
  GK: 'GOLEIRO', DEF: 'ZAGUEIRO', MID: 'MEIA_CENTRAL', ATT: 'ATACANTE',
}

function playerPositions(p: AssignablePlayer): DetailedPosition[] {
  const mine = [p.detailedPosition, ...(p.altPositions ?? [])].filter(
    (x): x is DetailedPosition => x != null,
  )
  return mine.length > 0 ? mine : [BAND_FALLBACK_POSITION[p.position]]
}

/** Smallest tactical distance between any of the player's positions and the slot's accepted set. */
function distanceToSlot(p: AssignablePlayer, accepted: DetailedPosition[]): number {
  let best = Infinity
  for (const mine of playerPositions(p)) {
    for (const acc of accepted) {
      best = Math.min(best, positionDistance(mine, acc))
    }
  }
  return best
}

/**
 * Client-side mirror of the backend SlotAssigner's greedy natural-first best-fit, used for
 * default arrangements (auto-pick, formation change, legacy lineups without persisted slots).
 * Slots are processed scarcest-first (fewest NATURAL candidates — any of the player's positions,
 * primary or alternate, accepted by the slot — in the initial pool, ties by slot index); each
 * slot takes the best remaining NATURAL candidate by overall desc then id desc — a genuine
 * natural always beats a higher-rated improviser. Only when no natural candidate remains does a
 * slot fall back to the closest player by tactical distance (same tie-break). Deterministic for a
 * given (players, formation).
 */
export function assignSlots(players: AssignablePlayer[], formation: string): Record<string, number> {
  const slots = pitchSlots(formation)
  const tier1Count = slots.map(slot => players.filter(p => isNatural(p, slot.accepted)).length)
  const order = slots.map((_, i) => i).sort((a, b) => tier1Count[a] - tier1Count[b] || a - b)
  const byRating = (a: AssignablePlayer, b: AssignablePlayer) =>
    b.overall - a.overall || b.id.localeCompare(a.id)
  const available = [...players]
  const out: Record<string, number> = {}
  for (const slotIndex of order) {
    if (available.length === 0) break
    const slot = slots[slotIndex]
    const naturals = available.filter(p => isNatural(p, slot.accepted))
    let choice: AssignablePlayer
    if (naturals.length > 0) {
      choice = [...naturals].sort(byRating)[0]
    } else {
      const distances = available.map(p => distanceToSlot(p, slot.accepted))
      const minDist = Math.min(...distances)
      const closest = available.filter((_, i) => distances[i] === minDist)
      choice = [...closest].sort(byRating)[0]
    }
    out[choice.id] = slotIndex
    available.splice(available.indexOf(choice), 1)
  }
  return out
}
