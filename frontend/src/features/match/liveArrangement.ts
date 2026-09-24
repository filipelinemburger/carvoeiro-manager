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
    // The role is blanked along the way: the incoming player's is the one they carry off the bench,
    // and the engine gives them the best familiar role for the slot they inherit instead. Leaving it
    // would have `appliedRole` announce a role nobody is going to play.
    return pair ? { ...pair.in, onPitch: true, slotIndex: p.slotIndex, role: '' } : p
  })
}

/**
 * The role the server is currently applying to `player`, as far as the client can honestly tell. It
 * only holds while the player still sits in the slot the server assigned them in: a player the manager
 * has dragged, and a substitute waiting to come on, both have their role recomputed for the slot they
 * end up in (spec Decision 6), so '' — "automática" — is the truthful answer for them. A role the slot
 * cannot take is not shown either, for the same reason.
 */
export function appliedRole(
  player: LivePlayer,
  slots: SlotMap,
  validArchetypes: readonly RoleArchetype[],
): RoleArchetype | '' {
  if (player.slotIndex === null || slots[player.playerId] !== player.slotIndex) return ''
  const role = player.role as RoleArchetype
  return validArchetypes.includes(role) ? role : ''
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

/**
 * Keeps a hand-set role override only for a player whose slot is identical in `before` and `after`.
 * A moved player's override may not be valid in their new slot, so they take the slot's natural role
 * instead (spec Decision 6) — exactly what the server does (`LiveMatchEngine#replaceArrangement`'s
 * same-index-and-formation check), including dropping the mover's override on a move into an empty slot.
 */
export function dropMovedRoles(before: SlotMap, after: SlotMap, roles: RoleOverrides): RoleOverrides {
  return Object.fromEntries(
    Object.entries(roles).filter(([id]) => after[id] === before[id]),
  )
}
