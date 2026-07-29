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

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** The two halves of a UUID as SIGNED 64-bit values, or null when `id` isn't a UUID. */
function uuidHalves(id: string): { msb: bigint; lsb: bigint } | null {
  if (!UUID_RE.test(id)) return null
  const hex = id.replace(/-/g, '')
  return {
    msb: BigInt.asIntN(64, BigInt('0x' + hex.slice(0, 16))),
    lsb: BigInt.asIntN(64, BigInt('0x' + hex.slice(16))),
  }
}

/**
 * Orders two player ids exactly as Java's `UUID.compareTo` does — the tie-break the backend
 * SlotAssigner uses when two players share an overall. That comparison is SIGNED on each 64-bit
 * half, so it is NOT the same as comparing the canonical strings: any id whose first hex digit is
 * >= 8 has a negative msb and sorts before every id with a positive one, the reverse of
 * lexicographic order. Roughly half of random UUIDs fall on that side, so a plain string compare
 * would resolve about half of all overall-ties opposite to the server and hand the client a
 * different XI. Non-UUID ids (test fixtures) fall back to string order.
 */
function compareIds(a: string, b: string): number {
  const ha = uuidHalves(a)
  const hb = uuidHalves(b)
  if (!ha || !hb) return a.localeCompare(b)
  if (ha.msb !== hb.msb) return ha.msb < hb.msb ? -1 : 1
  if (ha.lsb !== hb.lsb) return ha.lsb < hb.lsb ? -1 : 1
  return 0
}

/**
 * Client-side mirror of the backend SlotAssigner's greedy natural-first best-fit, used for
 * default arrangements (auto-pick, formation change, legacy lineups without persisted slots).
 * Slots are processed scarcest-first (fewest NATURAL candidates — any of the player's positions,
 * primary or alternate, accepted by the slot — in the initial pool, ties by slot index); each
 * slot takes the best remaining NATURAL candidate by overall desc then id desc — a genuine
 * natural always beats a higher-rated improviser. Only when no natural candidate remains does a
 * slot fall back to the closest player by tactical distance (same tie-break). A slot with ZERO
 * naturals sorts LAST, not first: it is unfillable rather than scarce, so nothing can starve it,
 * and letting it go first only lets its distance fallback steal a natural another slot needs.
 * Deterministic for a given (players, formation).
 */
export function assignSlots(players: AssignablePlayer[], formation: string): Record<string, number> {
  const slots = pitchSlots(formation)
  const tier1Count = slots.map(slot => players.filter(p => isNatural(p, slot.accepted)).length)
  // Mirrors SlotAssigner's comparator exactly: zero-natural slots are pushed past every
  // fillable slot; slots with >=1 natural keep ascending-count order, ties by slot index.
  const rank = (i: number) => (tier1Count[i] === 0 ? Number.MAX_SAFE_INTEGER : tier1Count[i])
  const order = slots.map((_, i) => i).sort((a, b) => rank(a) - rank(b) || a - b)
  const byRating = (a: AssignablePlayer, b: AssignablePlayer) =>
    b.overall - a.overall || compareIds(b.id, a.id)
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
