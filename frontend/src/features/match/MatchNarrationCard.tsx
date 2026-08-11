import { Alert, Box, Button, Paper, Stack, Typography } from '@mui/material'
import { KeyEventsSummary } from './KeyEventsSummary'
import { cardColors } from './clubKit'
import type { LiveEvent, LiveKitColors, LiveMatchStatus } from '../../api/types'

/** Icons for the live narration line. No consumer outside this file — kept module-private. */
const EVENT_ICON: Record<LiveEvent['type'], string> = {
  GOAL: '⚽', SET_PIECE_GOAL: '⚽', YELLOW_CARD: '🟨', RED_CARD: '🟥',
  CHANCE: '🎯', INJURY: '🚑', SUBSTITUTION: '🔁', TACTICS_CHANGE: '📋', HALF_TIME: '⏸️',
  PENALTY_GOAL: '⚽', FREE_KICK_GOAL: '⚽', CORNER_GOAL: '⚽', PENALTY_MISSED: '🧤',
}

/**
 * True whenever the action slot renders a button instead of the live narration line — the single
 * source of truth for both `ActionSlot`'s own branch selection below and `LiveMatchPage`'s gating
 * of the pause/play toggle and the Partida card's visibility on the other tabs. Exported (rather
 * than hand-mirrored at the call site) so the two can never drift: a status added here that shows
 * a button is automatically picked up by every caller, instead of relying on a comment to keep a
 * second, independently-written copy of this condition in sync.
 */
export function actionSlotShowsButton(minute: number, status: LiveMatchStatus): boolean {
  return (
    minute === 0
    || status === 'HALF_TIME'
    || status === 'PAUSED_INJURY'
    || status === 'PAUSED_RED'
    || status === 'FULL_TIME'
  )
}

/**
 * The action slot: exactly one of these renders at a time, in this priority order. `minute === 0`
 * is checked first because it implies `IN_PROGRESS` (the backend's `statusAt` returns a single
 * status), so it would otherwise be indistinguishable from real in-progress play.
 */
function ActionSlot({ minute, status, events, onResume, onFinish, actionPending, finishPending }: {
  minute: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onResume: () => void
  onFinish: () => void
  actionPending: boolean
  finishPending: boolean
}) {
  if (actionSlotShowsButton(minute, status)) {
    if (minute === 0) {
      return (
        <Button variant="contained" disabled={actionPending} onClick={onResume}>
          Iniciar partida
        </Button>
      )
    }

    if (status === 'HALF_TIME') {
      return (
        <Stack spacing={1} sx={{ alignItems: 'center' }}>
          <Button variant="contained" disabled={actionPending} onClick={onResume}>
            Iniciar 2º tempo
          </Button>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Ajuste substituições e táticas no card do seu time antes de começar.
          </Typography>
        </Stack>
      )
    }

    if (status === 'PAUSED_INJURY') {
      return (
        <Stack spacing={1} sx={{ alignItems: 'center', width: '100%' }}>
          <Alert severity="warning" sx={{ width: '100%' }}>Lesão! Faça uma substituição ou continue.</Alert>
          <Button variant="outlined" disabled={actionPending} onClick={onResume}>
            Continuar assim mesmo
          </Button>
        </Stack>
      )
    }

    if (status === 'PAUSED_RED') {
      return (
        <Stack spacing={1} sx={{ alignItems: 'center', width: '100%' }}>
          <Alert severity="error" sx={{ width: '100%' }}>Cartão vermelho! Seu time está com um a menos.</Alert>
          <Button variant="outlined" disabled={actionPending} onClick={onResume}>
            Continuar assim mesmo
          </Button>
        </Stack>
      )
    }

    if (status === 'FULL_TIME') {
      return (
        <Button variant="contained" disabled={finishPending} onClick={onFinish}>
          {finishPending ? 'Continuando…' : 'Continuar'}
        </Button>
      )
    }
  }

  // Open play with no action pending: show the live narration line instead of a button.
  // Always prefixed with the event's OWN minute (never the current clock minute) and never
  // blanked — after an "advance-to" skip the clock can sit past the last event's minute (e.g.
  // last event 87', clock 90'), and prefixing the event's own minute makes that self-evident
  // instead of misleadingly implying the last thing that happened was just now.
  const latest = events.length > 0 ? events[events.length - 1] : null
  return (
    <Typography>
      {latest ? `${latest.minute}' ${EVENT_ICON[latest.type]} ${latest.text}` : 'Bola rolando.'}
    </Typography>
  )
}

/**
 * Which side the card should be painted for, or `null` to leave it in the theme's neutral
 * `Paper` look. Deliberately returns the SIDE, not that side's kit: whether a club has usable
 * colours is `cardColors`' problem, and folding the two together would make a club with null
 * colours (a nullable pair of DB columns) silently read as "nobody is acting" and render neutral
 * — indistinguishable from half-time.
 *
 * Neutral in two cases, both deliberate: while the action slot shows a button (kickoff,
 * half-time, both forced pauses, full time) the card carries `Alert`s and buttons whose own
 * severity/brand colours must stay legible — and "who is acting" is meaningless with the match
 * stopped; with no events at all there is no acting team to read.
 */
function actingSide(minute: number, status: LiveMatchStatus, events: LiveEvent[]): 'home' | 'away' | null {
  if (actionSlotShowsButton(minute, status) || events.length === 0) return null
  return events[events.length - 1].home ? 'home' : 'away'
}

/**
 * The Partida tab's Championship-Manager-style card: the permanent key-events summary on top,
 * then the current moment (a live narration line, or whatever action the match is waiting for)
 * on a card tinted with the acting team's kit. Replaces the old ever-growing 90-minute
 * commentary feed, which buried who-scored-when under near-miss chatter.
 *
 * `onResume` is the container's `resume()` — its in-flight guard lives at that source, not here.
 * This component never reimplements it; `actionPending` only drives the button's `disabled`
 * prop, it does not gate whether `onResume` gets called.
 */
export function MatchNarrationCard({
  minute, status, events, homeKit, awayKit, onResume, onFinish, actionPending, finishPending,
}: {
  minute: number
  status: LiveMatchStatus
  events: LiveEvent[]
  homeKit: LiveKitColors | null
  awayKit: LiveKitColors | null
  onResume: () => void
  onFinish: () => void
  actionPending: boolean
  finishPending: boolean
}) {
  // `cardColors` — the same guarded helper the club cards use — turns a possibly null/non-hex
  // kit into a safe {bg, fg} pair. Never hand-roll this: an unguarded value reaches
  // `parseInt(undefined, 16)` and paints the card `rgb(NaN, NaN, NaN)`.
  const side = actingSide(minute, status, events)
  const tint = side ? cardColors(side === 'home' ? homeKit : awayKit) : null

  return (
    // A fragment, not a wrapper element: the key-events summary has to sit ABOVE the Paper
    // (not just above the narration line inside it), and keeping both here rather than hoisting
    // the summary to LiveMatchPage leaves this component the single owner of when the card
    // renders at all — the page's layout and its `feedTab === 0 || showsActionButton` gate are
    // untouched.
    <>
      <Box data-testid="key-events" sx={{ mb: 1 }}>
        <KeyEventsSummary events={events} />
      </Box>
      <Paper
        data-testid="narration-card"
        elevation={0}
        sx={{
          p: 2, borderRadius: 2.5, boxShadow: '0 2px 10px rgba(0,0,0,0.12)', textAlign: 'center',
          // Spread only when tinted, so the neutral case keeps the theme's own Paper colours
          // rather than being overridden with a hard-coded equivalent. `color` rides along with
          // `bgcolor` and is never omitted: the narration line inherits it, and cardColors pairs
          // the two for contrast — the theme's default text colour has no such guarantee against
          // an arbitrary club kit.
          ...(tint ? { bgcolor: tint.bg, color: tint.fg } : {}),
        }}
      >
        <Stack sx={{ alignItems: 'center', justifyContent: 'center', minHeight: 56 }}>
          <ActionSlot
            minute={minute}
            status={status}
            events={events}
            onResume={onResume}
            onFinish={onFinish}
            actionPending={actionPending}
            finishPending={finishPending}
          />
        </Stack>
      </Paper>
    </>
  )
}
