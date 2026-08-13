import { useEffect, useRef, useState } from 'react'
import { Box } from '@mui/material'
import { pitchSlots, type Slot, type Line } from './pitchSlots'
import { PlayerToken, type TokenBadges } from './PlayerToken'
import { tokenColors, type Kit } from './kit'
import { DETAILED_POSITION_SHORT, isNatural, positionsShortLabel } from '../players/positionLabels'
import type { DetailedPosition } from '../../api/types'

// A drop lands on the nearest droppable slot whose center is within this fraction of the
// pitch's rendered width; farther away, the token snaps back.
const DROP_RADIUS_FRACTION = 0.12

// A pointer interaction that moves less than this many pixels between down and up resolves as a
// tap (fires onSlotClick) rather than a drag (fires onSwap).
const CLICK_MOVE_THRESHOLD = 6

// A pointer held this long without moving past CLICK_MOVE_THRESHOLD opens the context menu —
// the touch equivalent of a right-click.
const LONG_PRESS_MS = 500

export type PlacedPlayer = {
  id: string
  surname: string
  position: 'GK' | 'DEF' | 'MID' | 'ATT'
  /** Fine-grained position; when set and not accepted by the slot, the token gets a warn tint. */
  detailedPosition?: DetailedPosition | null
  /**
   * Alternate playable positions. A player is natural for a slot when EITHER `detailedPosition`
   * OR any of these is accepted — mirrors `isNatural` (positionLabels.ts), the same rule the
   * slot picker uses, so the pitch tint never contradicts it.
   */
  altPositions?: DetailedPosition[] | null
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
//
// Slot-based lineup validation permits cross-line arrangements (e.g. 4 defenders fielded
// against a formation with only 3 DEF slots — the "extra" defender legally occupies a slot
// on another line). When a line has more players than that formation has slots for, the
// leftover players are collected as overflow and dropped into whichever slots (on any line)
// are still empty once every line has been zipped, in slot order — so every player in
// `team.players` always lands somewhere, never silently vanishing.
function assign(team: TeamOnPitch): Positioned[] {
  const slots = pitchSlots(team.formation)
  const placed = new Map<number, PlacedPlayer>()
  const overflow: PlacedPlayer[] = []
  for (const line of LINES) {
    const lineSlots = slots.map((s, i) => ({ s, i })).filter(x => x.s.line === line)
    const linePlayers = team.players.filter(p => p.position === line)
    lineSlots.forEach(({ i }, k) => {
      if (linePlayers[k]) placed.set(i, linePlayers[k])
    })
    if (linePlayers.length > lineSlots.length) {
      overflow.push(...linePlayers.slice(lineSlots.length))
    }
  }
  const emptySlotIndexes = slots.map((_, i) => i).filter(i => !placed.has(i))
  overflow.forEach((p, k) => {
    if (emptySlotIndexes[k] !== undefined) placed.set(emptySlotIndexes[k], p)
  })
  return slots.map((slot, i) => ({ slot, number: i + 1, player: placed.get(i) ?? null }))
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

// `maxMoved` tracks the farthest the pointer has travelled from `startX/Y` at any point during
// the interaction (not just its final resting spot) — see the tap-vs-drag resolution below.
type Drag = { fromSlot: number; startX: number; startY: number; x: number; y: number; maxMoved: number }

function TeamLayer({ team, mirror, onSwap, onSlotClick, onSlotContextMenu, containerRef }: {
  team: TeamOnPitch
  mirror: boolean
  onSwap?: (fromSlot: number, toSlot: number) => void
  onSlotClick?: (slotIndex: number) => void
  onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void
  containerRef?: React.RefObject<HTMLDivElement | null>
}) {
  const colors = tokenColors(team.kit)
  const [drag, setDrag] = useState<Drag | null>(null)
  const positioned = place(team)

  // Long-press bookkeeping. One set of refs for the whole layer, like `drag` above: only one
  // pointer interaction is ever in flight, so per-slot refs would buy nothing.
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const longPressFired = useRef(false)
  const pressStart = useRef<{ x: number; y: number } | null>(null)

  function cancelLongPress() {
    if (longPressTimer.current !== null) {
      clearTimeout(longPressTimer.current)
      longPressTimer.current = null
    }
    pressStart.current = null
  }

  // A timer armed on pointerdown outlives the component if it unmounts mid-hold (route change,
  // parent re-render) — React does not touch a bare setTimeout.
  useEffect(() => () => cancelLongPress(), [])

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
        // The slot's primary accepted position labels the token; a player who isn't NATURAL for
        // the slot — neither their primary detailedPosition nor any altPositions is accepted —
        // gets the out-of-position warn tint. Same rule as the slot picker (`isNatural`), so a
        // multi-position player fielded in an alternate position is never flagged as improvised
        // when the picker itself would call them natural.
        const posLabel = DETAILED_POSITION_SHORT[slot.accepted[0]]
        const outOfPosition = !!player?.detailedPosition && !isNatural(player, slot.accepted)
        const draggable = Boolean(onSwap) && !mirror && i !== 0 && player !== null
        // Any slot (including GK and empties) is tappable when a click handler is supplied; a
        // tap is resolved below by movement distance, not by whether the slot is draggable.
        const tappable = Boolean(onSlotClick) && !mirror
        const interactive = draggable || tappable
        // The context menu is offered only for slots that actually hold a player — there is no
        // Função/Foco to edit on an empty placeholder.
        const contextual = Boolean(onSlotContextMenu) && !mirror && player !== null
        const pointerActive = interactive || contextual
        const dragging = drag?.fromSlot === i
        return (
          <Box
            key={player?.id ?? `slot-${number}`}
            data-slot-index={i}
            data-drop-candidate={dropCandidate === i ? 'true' : undefined}
            onContextMenu={contextual ? (e: React.MouseEvent) => {
              e.preventDefault()
              onSlotContextMenu?.(i, e.clientX, e.clientY)
            } : undefined}
            onPointerDown={pointerActive ? e => {
              e.currentTarget.setPointerCapture?.(e.pointerId)
              // The long-press refs are shared by every slot in this layer (see above). A second
              // pointer starting while one is still pending must not leave the first timer
              // orphaned — an uncleared timer would fire late, into whichever slot's press state
              // happens to occupy the shared refs by then, and swallow an unrelated tap.
              cancelLongPress()
              longPressFired.current = false
              if (contextual) {
                const { clientX, clientY } = e
                pressStart.current = { x: clientX, y: clientY }
                longPressTimer.current = setTimeout(() => {
                  longPressTimer.current = null
                  longPressFired.current = true
                  onSlotContextMenu?.(i, clientX, clientY)
                }, LONG_PRESS_MS)
              }
              if (interactive) {
                setDrag({ fromSlot: i, startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY, maxMoved: 0 })
              }
            } : undefined}
            onPointerMove={pointerActive ? e => {
              // Moving past the tap threshold turns the interaction into a drag, so the pending
              // long-press is abandoned rather than firing mid-drag.
              if (pressStart.current) {
                const moved = Math.hypot(e.clientX - pressStart.current.x, e.clientY - pressStart.current.y)
                if (moved > CLICK_MOVE_THRESHOLD) cancelLongPress()
              }
              setDrag(d => {
                if (!d || d.fromSlot !== i) return d
                const moved = Math.hypot(e.clientX - d.startX, e.clientY - d.startY)
                return { ...d, x: e.clientX, y: e.clientY, maxMoved: Math.max(d.maxMoved, moved) }
              })
            } : undefined}
            onPointerUp={pointerActive ? e => {
              cancelLongPress()
              // A long-press already opened the menu; the release must not ALSO pop the slot
              // picker on top of it.
              if (longPressFired.current) {
                longPressFired.current = false
                setDrag(null)
                return
              }
              if (!drag || drag.fromSlot !== i) return
              // Resolve tap-vs-drag by the farthest the pointer travelled during the WHOLE
              // interaction, not just the net start→release displacement — a user who drags a
              // token away and deliberately brings it back near its origin (changing their mind)
              // must still be treated as a (cancelled) drag, not a tap that pops the picker open.
              const upMoved = Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY)
              const moved = Math.max(drag.maxMoved, upMoved)
              setDrag(null)
              if (moved <= CLICK_MOVE_THRESHOLD) {
                onSlotClick?.(i)
                return
              }
              if (!draggable) return
              const target = dropTarget(e.clientX, e.clientY, i)
              if (target !== null) onSwap?.(i, target)
            } : undefined}
            onPointerCancel={pointerActive ? () => {
              cancelLongPress()
              longPressFired.current = false
              setDrag(null)
            } : undefined}
            sx={{
              position: 'absolute',
              left: `${xPct}%`,
              top: `${yPct}%`,
              transform: dragging && drag
                ? `translate(-50%, -50%) translate(${drag.x - drag.startX}px, ${drag.y - drag.startY}px)`
                : 'translate(-50%, -50%)',
              zIndex: dragging ? 10 : undefined,
              cursor: draggable ? 'grab' : tappable ? 'pointer' : undefined,
              touchAction: pointerActive ? 'none' : undefined,
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
                naturalPositions={outOfPosition ? positionsShortLabel(player) : null}
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

export function Pitch({ home, away, onSwap, onSlotClick, onSlotContextMenu }: {
  home: TeamOnPitch
  away?: TeamOnPitch
  /**
   * Presence makes the home team's outfield tokens draggable. Called with the origin and
   * target slot indexes on a successful drop; the parent decides swap-vs-move.
   */
  onSwap?: (fromSlot: number, toSlot: number) => void
  /**
   * Fired with a home-team slot index on a TAP — a pointer interaction that resolves as a click
   * rather than a drag (see `CLICK_MOVE_THRESHOLD`). Fires for every home slot, including the GK
   * slot and empty placeholders; dragging (via `onSwap`) is unaffected.
   */
  onSlotClick?: (slotIndex: number) => void
  /**
   * Fired with a home-team slot index and the pointer's client coordinates on a right-click or
   * a ~500ms long-press, for slots that HOLD A PLAYER. A long-press suppresses the `onSlotClick`
   * that its release would otherwise produce; dragging is unaffected.
   */
  onSlotContextMenu?: (slotIndex: number, clientX: number, clientY: number) => void
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
      <TeamLayer team={home} mirror={false} onSwap={onSwap} onSlotClick={onSlotClick} onSlotContextMenu={onSlotContextMenu} containerRef={containerRef} />
      {away && <TeamLayer team={away} mirror />}
    </Box>
  )
}
