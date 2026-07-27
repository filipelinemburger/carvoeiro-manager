import { useRef, useState } from 'react'
import { Box } from '@mui/material'
import { pitchSlots, type Slot, type Line } from './pitchSlots'
import { PlayerToken, type TokenBadges } from './PlayerToken'
import { tokenColors, type Kit } from './kit'
import { DETAILED_POSITION_SHORT } from '../players/positionLabels'
import type { DetailedPosition } from '../../api/types'

// A drop lands on the nearest droppable slot whose center is within this fraction of the
// pitch's rendered width; farther away, the token snaps back.
const DROP_RADIUS_FRACTION = 0.12

export type PlacedPlayer = {
  id: string
  surname: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  /** Fine-grained position; when set and not accepted by the slot, the token gets a warn tint. */
  detailedPosition?: DetailedPosition | null
  /**
   * Explicit formation slot (index into pitchSlots(formation)). When EVERY player of a team
   * carries one, placement uses it; otherwise the legacy line-zip below runs.
   */
  slotIndex?: number
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

// Places players by their explicit slotIndex; slots nobody claims stay on the pitch as empty
// placeholders. Used when every player carries a slotIndex; otherwise `assign` line-zips.
function placeExplicit(team: TeamOnPitch): Positioned[] {
  const slots = pitchSlots(team.formation)
  const bySlot = new Map(team.players.map(p => [p.slotIndex as number, p]))
  return slots.map((slot, i) => ({ slot, number: i + 1, player: bySlot.get(i) ?? null }))
}

function place(team: TeamOnPitch): Positioned[] {
  return team.players.every(p => p.slotIndex != null) ? placeExplicit(team) : assign(team)
}

type Drag = { fromSlot: number; startX: number; startY: number; x: number; y: number }

function TeamLayer({ team, mirror, onSwap, containerRef }: {
  team: TeamOnPitch
  mirror: boolean
  onSwap?: (fromSlot: number, toSlot: number) => void
  containerRef?: React.RefObject<HTMLDivElement | null>
}) {
  const colors = tokenColors(team.kit)
  const [drag, setDrag] = useState<Drag | null>(null)
  const positioned = place(team)

  // Nearest droppable slot center within DROP_RADIUS_FRACTION of the pitch width, in screen
  // pixels; the origin slot and slot 0 (GK) are never candidates. Null = snap back.
  function dropTarget(clientX: number, clientY: number, fromSlot: number): number | null {
    const rect = containerRef?.current?.getBoundingClientRect()
    if (!rect || rect.width === 0) return null
    const radius = rect.width * DROP_RADIUS_FRACTION
    // Reduce (rather than a mutable outer `let` reassigned inside forEach) so TS can narrow
    // `best` cleanly at the end — a `let` mutated inside a nested closure loses that narrowing.
    const best = positioned.reduce<{ slot: number; d: number } | null>((acc, { slot }, i) => {
      if (i === fromSlot || i === 0) return acc
      const cx = rect.left + (mirror ? 1 - slot.x : slot.x) * rect.width
      const cy = rect.top + (mirror ? 1 - slot.y : slot.y) * rect.height
      const d = Math.hypot(clientX - cx, clientY - cy)
      return d <= radius && (acc === null || d < acc.d) ? { slot: i, d } : acc
    }, null)
    return best === null ? null : best.slot
  }

  const dropCandidate = drag ? dropTarget(drag.x, drag.y, drag.fromSlot) : null

  return (
    <>
      {positioned.map(({ slot, number, player }, i) => {
        const yPct = (mirror ? 1 - slot.y : slot.y) * 100
        const xPct = (mirror ? 1 - slot.x : slot.x) * 100
        // The slot's primary accepted position labels the token; a player whose detailed position
        // isn't in the slot's accepted set gets the out-of-position warn tint.
        const posLabel = DETAILED_POSITION_SHORT[slot.accepted[0]]
        const outOfPosition = !!player?.detailedPosition && !slot.accepted.includes(player.detailedPosition)
        const draggable = Boolean(onSwap) && !mirror && i !== 0 && player !== null
        const dragging = drag?.fromSlot === i
        return (
          <Box
            key={player?.id ?? `slot-${number}`}
            data-slot-index={i}
            data-drop-candidate={dropCandidate === i ? 'true' : undefined}
            onPointerDown={draggable ? e => {
              e.currentTarget.setPointerCapture?.(e.pointerId)
              setDrag({ fromSlot: i, startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY })
            } : undefined}
            onPointerMove={draggable ? e => {
              setDrag(d => (d && d.fromSlot === i ? { ...d, x: e.clientX, y: e.clientY } : d))
            } : undefined}
            onPointerUp={draggable ? e => {
              if (!drag || drag.fromSlot !== i) return
              const target = dropTarget(e.clientX, e.clientY, i)
              setDrag(null)
              if (target !== null) onSwap?.(i, target)
            } : undefined}
            onPointerCancel={draggable ? () => setDrag(null) : undefined}
            sx={{
              position: 'absolute',
              left: `${xPct}%`,
              top: `${yPct}%`,
              transform: dragging && drag
                ? `translate(-50%, -50%) translate(${drag.x - drag.startX}px, ${drag.y - drag.startY}px)`
                : 'translate(-50%, -50%)',
              zIndex: dragging ? 10 : undefined,
              cursor: draggable ? 'grab' : undefined,
              touchAction: draggable ? 'none' : undefined,
              ...(dropCandidate === i && { borderRadius: 1, boxShadow: '0 0 0 3px #f7d417' }),
            }}
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

export function Pitch({ home, away, onSwap }: {
  home: TeamOnPitch
  away?: TeamOnPitch
  /**
   * Presence makes the home team's outfield tokens draggable. Called with the origin and
   * target slot indexes on a successful drop; the parent decides swap-vs-move.
   */
  onSwap?: (fromSlot: number, toSlot: number) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  return (
    <Box
      ref={containerRef}
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
      <TeamLayer team={home} mirror={false} onSwap={onSwap} containerRef={containerRef} />
      {away && <TeamLayer team={away} mirror />}
    </Box>
  )
}
