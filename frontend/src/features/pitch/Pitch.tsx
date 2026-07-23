import { Box } from '@mui/material'
import { pitchSlots, type Slot, type Line } from './pitchSlots'
import { PlayerToken, type TokenBadges } from './PlayerToken'
import { tokenColors, type Kit } from './kit'
import { DETAILED_POSITION_SHORT } from '../players/positionLabels'
import type { DetailedPosition } from '../../api/types'

export type PlacedPlayer = {
  id: string
  surname: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  /** Fine-grained position; when set and not accepted by the slot, the token gets a warn tint. */
  detailedPosition?: DetailedPosition | null
  fitness: number
  badges?: TokenBadges
}
export type TeamOnPitch = { formation: string; kit: Kit; players: PlacedPlayer[]; showNames?: boolean }

const LINES: Line[] = ['GK', 'DEF', 'MID', 'ATT']

type Positioned = { slot: Slot; number: number; player: PlacedPlayer | null }

// Zip a team's players into their formation slots, line by line, assigning a
// stable shirt number from the slot's global order (GK=1, then DEF/MID/ATT).
// Every one of the 11 slots is emitted: a slot with no matching player is kept
// on the pitch as an empty placeholder (player === null) so the formation shape
// is always fully drawn.
function assign(team: TeamOnPitch): Positioned[] {
  const slots = pitchSlots(team.formation)
  const out: Positioned[] = []
  for (const line of LINES) {
    const lineSlots = slots.map((s, i) => ({ s, i })).filter(x => x.s.line === line)
    const linePlayers = team.players.filter(p => p.position === line)
    lineSlots.forEach(({ s, i }, k) => {
      out.push({ slot: s, number: i + 1, player: linePlayers[k] ?? null })
    })
  }
  return out
}

function TeamLayer({ team, mirror }: { team: TeamOnPitch; mirror: boolean }) {
  const colors = tokenColors(team.kit)
  return (
    <>
      {assign(team).map(({ slot, number, player }) => {
        const yPct = (mirror ? 1 - slot.y : slot.y) * 100
        const xPct = (mirror ? 1 - slot.x : slot.x) * 100
        // The slot's primary accepted position labels the token; a player whose detailed position
        // isn't in the slot's accepted set gets the out-of-position warn tint.
        const posLabel = DETAILED_POSITION_SHORT[slot.accepted[0]]
        const outOfPosition = !!player?.detailedPosition && !slot.accepted.includes(player.detailedPosition)
        return (
          <Box
            key={player?.id ?? `slot-${number}`}
            sx={{ position: 'absolute', left: `${xPct}%`, top: `${yPct}%`, transform: 'translate(-50%, -50%)' }}
          >
            {player ? (
              <PlayerToken
                number={number}
                surname={team.showNames ? player.surname : undefined}
                fill={colors.fill}
                text={colors.text}
                fitness={player.fitness}
                badges={player.badges}
                posLabel={posLabel}
                outOfPosition={outOfPosition}
              />
            ) : (
              <PlayerToken number={number} empty fill={colors.fill} text={colors.text} fitness={0} posLabel={posLabel} />
            )}
          </Box>
        )
      })}
    </>
  )
}

export function Pitch({ home, away }: { home: TeamOnPitch; away?: TeamOnPitch }) {
  return (
    <Box
      data-testid="pitch"
      sx={{ position: 'relative', width: '100%', maxWidth: 440, mx: 'auto', aspectRatio: '2 / 3', borderRadius: 1, overflow: 'hidden', backgroundColor: '#2b8449' }}
    >
      <Box component="svg" viewBox="0 0 100 150" preserveAspectRatio="none" sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <g fill="none" stroke="#ffffff" strokeWidth={0.4} opacity={0.85}>
          <rect x={3} y={3} width={94} height={144} />
          <line x1={3} y1={75} x2={97} y2={75} />
          <circle cx={50} cy={75} r={12} />
          <rect x={28} y={3} width={44} height={18} />
          <rect x={28} y={129} width={44} height={18} />
        </g>
      </Box>
      <TeamLayer team={home} mirror={false} />
      {away && <TeamLayer team={away} mirror />}
    </Box>
  )
}
