import { Box } from '@mui/material'
import { LineupColumn } from './LineupColumn'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { sidesOf } from './matchSides'
import type { LiveKitColors, LiveMatchState, LivePlayer } from '../../api/types'

/** Builds one side's pitch view-model from its live on-pitch roster. Module-scope (no
 * component state) so it's freely reusable for either side without a closure. Exported for
 * `OpponentScoutingPanel`, which renders a single-team pitch for the opponent's XI. */
export function buildTeam(players: LivePlayer[], formation: string, colors: LiveKitColors): TeamOnPitch {
  return {
    formation,
    kit: colors,
    showNames: true,
    players: players.map((p): PlacedPlayer => ({
      id: p.playerId,
      surname: p.name,
      position: p.position as PlacedPlayer['position'],
      fitness: p.fitness,
      badges: p.hobbled ? { injured: true } : undefined,
      detailedPosition: p.detailedPosition,
      altPositions: p.altPositions,
      // Pitch places explicitly only when EVERY player has an index; one null falls back to the line-zip.
      slotIndex: p.slotIndex ?? undefined,
    })),
  }
}

/**
 * "Escalações": home lineup list | pitch | away lineup list. Replaces both the old Campo tab and
 * the minute-0 pre-match lineups — it renders identically at minute 0 and mid-match, since the
 * lineups are just the current on-pitch XIs.
 *
 * Both the lists and the pitch derive their sides from `sidesOf` (`matchSides.ts`), the single
 * home/away <-> managed/opponent mapping — this is what makes the historical inversion bug
 * (present twice, independently, before that helper existed) structurally impossible here.
 */
export function LineupsTab({ state, onPlayerClick }: {
  state: LiveMatchState
  onPlayerClick?: (playerId: string, isOwnPlayer: boolean) => void
}) {
  const sides = sidesOf(state)
  const homeTeam = buildTeam(sides.home.players, sides.home.formation, sides.home.kit)
  const awayTeam = buildTeam(sides.away.players, sides.away.formation, sides.away.kit)

  return (
    <Box sx={{
      display: 'grid',
      gap: 2,
      gridTemplateColumns: { xs: '1fr', md: 'minmax(0,1fr) auto minmax(0,1fr)' },
    }}>
      <LineupColumn side={sides.home} onPlayerClick={onPlayerClick} />
      <Box sx={{ width: { xs: '100%', md: 320 } }}>
        <Pitch home={homeTeam} away={awayTeam} />
      </Box>
      <LineupColumn side={sides.away} onPlayerClick={onPlayerClick} />
    </Box>
  )
}
