import type { DetailedPosition, PlayerResponse, TrainableAttribute, TrainingIntensity } from '../../../api/types'

/** The Treino tab's working state: what each player is currently set to train, keyed by playerId. */
export type TrainingAssignmentsState = Record<
  string,
  { focus: TrainableAttribute | null; intensity: TrainingIntensity }
>

/** One "everyone in this position trains X at intensity Y" rule from the bulk card. */
export type BulkTrainingRule = {
  position: DetailedPosition
  focus: TrainableAttribute | null
  intensity: TrainingIntensity
}

/**
 * Applies the bulk card's rules to the working assignments, returning a NEW record.
 *
 * Matching is on the player's MAIN position (`detailedPosition`) only — `altPositions` are
 * deliberately ignored, because "all my centre-backs" should not sweep up a full-back who
 * merely can cover there. A player with no detailed position (legacy payload) matches nothing
 * and keeps their current assignment: rules are keyed on detailed positions, and the coarse
 * fallback the table shows in its Pos column is not one of them.
 *
 * A rule with `focus: null` CLEARS that position's training focus. That is an assignment, not
 * a no-op — "these players train nothing in particular" is a real instruction.
 */
export function applyBulkRules(
  assignments: TrainingAssignmentsState,
  players: PlayerResponse[],
  rules: BulkTrainingRule[],
): TrainingAssignmentsState {
  const byPosition = new Map(rules.map(rule => [rule.position, rule]))
  const next: TrainingAssignmentsState = { ...assignments }
  for (const player of players) {
    if (!player.detailedPosition) continue
    const rule = byPosition.get(player.detailedPosition)
    if (!rule) continue
    next[player.id] = { focus: rule.focus, intensity: rule.intensity }
  }
  return next
}
