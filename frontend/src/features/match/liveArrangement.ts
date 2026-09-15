import type { LivePlayer, RoleArchetype } from '../../api/types'
import { assignSlots, type AssignablePlayer } from '../pitch/slotAssign'

export interface SubPair {
  out: LivePlayer
  in: LivePlayer
}

/** playerId -> slot index into pitchSlots(formation). */
export type SlotMap = Record<string, number>
/** playerId -> archetype, for roles the manager set by hand only. */
export type RoleOverrides = Record<string, RoleArchetype>

/**
 * The on-pitch roster as it will stand once the queued substitutions land. Each outgoing player is
 * replaced in place by the incoming one, who inherits the outgoing player's slot index — the rule the
 * engine applies (spec Decision 5) — so the Posições tab shows the pitch the server will actually grade.
 */
export function workingRoster(onPitch: LivePlayer[], queue: SubPair[]): LivePlayer[] {
  return onPitch.map(p => {
    const pair = queue.find(q => q.out.playerId === p.playerId)
    return pair ? { ...pair.in, onPitch: true, slotIndex: p.slotIndex } : p
  })
}

const toAssignable = (p: LivePlayer): AssignablePlayer => ({
  id: p.playerId,
  position: p.position as AssignablePlayer['position'],
  detailedPosition: p.detailedPosition,
  altPositions: p.altPositions,
  overall: p.overall,
})

/**
 * Where editing starts from. When the formation is the one the server arranged against and every player
 * carries a server index, the server's arrangement IS the baseline. Otherwise — a formation change, or a
 * payload without indexes — it is `assignSlots`, the client mirror of the server's greedy assignment,
 * which is exactly the relocation the engine performs for a formation change.
 */
export function baselineArrangement(roster: LivePlayer[], formation: string, serverFormation: string): SlotMap {
  if (formation === serverFormation && roster.every(p => p.slotIndex !== null)) {
    return Object.fromEntries(roster.map(p => [p.playerId, p.slotIndex as number]))
  }
  return assignSlots(roster.map(toAssignable), formation)
}

/** Swaps whoever holds `fromSlot` with whoever holds `toSlot`; into an empty slot it is a plain move. */
export function swapSlots(slots: SlotMap, fromSlot: number, toSlot: number): SlotMap {
  const fromId = Object.keys(slots).find(id => slots[id] === fromSlot)
  if (fromId === undefined) return slots
  const toId = Object.keys(slots).find(id => slots[id] === toSlot)
  const next: SlotMap = { ...slots, [fromId]: toSlot }
  if (toId !== undefined) next[toId] = fromSlot
  return next
}

export function sameSlots(a: SlotMap, b: SlotMap): boolean {
  const keys = Object.keys(a)
  return keys.length === Object.keys(b).length && keys.every(k => a[k] === b[k])
}
