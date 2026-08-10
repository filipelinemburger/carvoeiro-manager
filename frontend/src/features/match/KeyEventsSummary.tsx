import { Divider, Stack, Typography } from '@mui/material'
import { summarizeKeyEvents, type DisciplineLine, type SideKeyEvents } from './matchKeyEvents'
import type { LiveEvent } from '../../api/types'

const DISCIPLINE_ICON: Record<DisciplineLine['kind'], string> = {
  YELLOW: '🟨',
  RED: '🟥',
  INJURY: '🚑',
  PENALTY_MISSED: '🧤',
}

/**
 * One side's key-events column: a scorers block, then a discipline block. Either (or both) can
 * be empty — an empty block renders nothing, there is no "sem gols" placeholder, so an
 * uneventful side simply leaves its column blank.
 *
 * Each line's name and minute(s) are kept inside a SINGLE `Typography` on purpose (same
 * contract as `MatchInfoLine`'s stadium-and-date line): tests match the whole line as one text
 * node, e.g. `getByText(/Samba 21', 42'/)`, so splitting name and minutes across elements would
 * silently break that assertion.
 */
function SideColumn({ side }: { side: SideKeyEvents }) {
  return (
    <Stack spacing={0.5} sx={{ flex: 1, minWidth: 0 }}>
      {side.scorers.map(scorer => (
        <Typography key={scorer.name} variant="body2">
          {scorer.name} {scorer.minutes.map(m => `${m}'`).join(', ')}
        </Typography>
      ))}
      {side.discipline.map((d, i) => (
        <Typography key={`${d.name}-${d.minute}-${i}`} variant="body2" sx={{ color: 'text.secondary' }}>
          {DISCIPLINE_ICON[d.kind]} {d.name} {d.minute}'
        </Typography>
      ))}
    </Stack>
  )
}

/**
 * The permanent key-events block under the narration/action slot: two columns (home left, away
 * right) separated by a thin divider, each grouped by `summarizeKeyEvents` (`matchKeyEvents.ts`,
 * Task 4) — this component only renders that grouping, it never re-derives it.
 */
export function KeyEventsSummary({ events }: { events: LiveEvent[] }) {
  const keyEvents = summarizeKeyEvents(events)

  return (
    <Stack
      direction="row"
      spacing={2}
      divider={<Divider orientation="vertical" flexItem />}
      sx={{ mt: 1.5 }}
    >
      <SideColumn side={keyEvents.home} />
      <SideColumn side={keyEvents.away} />
    </Stack>
  )
}
