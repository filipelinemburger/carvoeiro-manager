import { pitchSlots } from './pitchSlots'
import type { DetailedPosition } from '../../api/types'

export type AssignablePlayer = {
  id: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  detailedPosition?: DetailedPosition | null
  overall: number
}

/**
 * Client-side mirror of the backend SlotAssigner's greedy tiered best-fit, used for default
 * arrangements (auto-pick, formation change, legacy lineups without persisted slots). Slots are
 * processed scarcest-first (fewest exact-detailed candidates in the initial pool, ties by slot
 * index); each slot takes the best remaining candidate by tier — 1 = the slot accepts the
 * player's detailed position, 2 = same coarse line, 3 = anyone — ties by overall desc then id.
 * Deterministic for a given (players, formation). Exact tie-break parity with the backend's
 * UUID ordering is not required (arrangements are persisted from whichever side computed them);
 * determinism is.
 */
export function assignSlots(players: AssignablePlayer[], formation: string): Record<string, number> {
  const slots = pitchSlots(formation)
  const tier1Count = slots.map(
    slot => players.filter(p => p.detailedPosition && slot.accepted.includes(p.detailedPosition)).length,
  )
  const order = slots.map((_, i) => i).sort((a, b) => tier1Count[a] - tier1Count[b] || a - b)
  const byRating = (a: AssignablePlayer, b: AssignablePlayer) =>
    b.overall - a.overall || b.id.localeCompare(a.id)
  const available = [...players]
  const out: Record<string, number> = {}
  for (const slotIndex of order) {
    if (available.length === 0) break
    const slot = slots[slotIndex]
    const tier1 = available.filter(p => p.detailedPosition && slot.accepted.includes(p.detailedPosition))
    const tier2 = available.filter(p => p.position === slot.line)
    const pool = tier1.length > 0 ? tier1 : tier2.length > 0 ? tier2 : available
    const choice = [...pool].sort(byRating)[0]
    out[choice.id] = slotIndex
    available.splice(available.indexOf(choice), 1)
  }
  return out
}
