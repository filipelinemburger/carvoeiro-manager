import { Alert, Button, Paper, Stack, Typography } from '@mui/material'
import { KeyEventsSummary } from './KeyEventsSummary'
import type { LiveEvent, LiveMatchStatus } from '../../api/types'

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
 * The Partida tab's Championship-Manager-style card: the current moment (a live narration line,
 * or whatever action the match is waiting for) on top, the permanent key-events summary below.
 * Replaces the old ever-growing 90-minute commentary feed, which buried who-scored-when under
 * near-miss chatter.
 *
 * `onResume` is the container's `resume()` — its in-flight guard lives at that source, not here.
 * This component never reimplements it; `actionPending` only drives the button's `disabled`
 * prop, it does not gate whether `onResume` gets called.
 */
export function MatchNarrationCard({
  minute, status, events, onResume, onFinish, actionPending, finishPending,
}: {
  minute: number
  status: LiveMatchStatus
  events: LiveEvent[]
  onResume: () => void
  onFinish: () => void
  actionPending: boolean
  finishPending: boolean
}) {
  return (
    <Paper
      elevation={0}
      sx={{ p: 2, borderRadius: 2.5, boxShadow: '0 2px 10px rgba(0,0,0,0.12)', textAlign: 'center' }}
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
      <KeyEventsSummary events={events} />
    </Paper>
  )
}
