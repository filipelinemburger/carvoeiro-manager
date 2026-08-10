import { Box, Stack, Typography } from '@mui/material'
import { kitsClash, tokenColors } from '../pitch/kit'
import { safeKit } from './clubKit'
import type { LiveKitColors, MatchStats, TeamStats } from '../../api/types'

type StatKey = keyof TeamStats

const ROWS: readonly { key: StatKey; label: string; suffix?: string }[] = [
  { key: 'possession', label: 'Posse de bola', suffix: '%' },
  { key: 'shots', label: 'Finalizações' },
  { key: 'shotsOnTarget', label: 'Finalizações no gol' },
  { key: 'saves', label: 'Defesas' },
  { key: 'corners', label: 'Escanteios' },
  { key: 'offsides', label: 'Impedimentos' },
  { key: 'fouls', label: 'Faltas' },
  { key: 'yellowCards', label: 'Cartões amarelos' },
  { key: 'redCards', label: 'Cartões vermelhos' },
]

const EMPTY_TEAM_STATS: TeamStats = {
  possession: 50,
  shots: 0,
  shotsOnTarget: 0,
  saves: 0,
  corners: 0,
  offsides: 0,
  fouls: 0,
  yellowCards: 0,
  redCards: 0,
}

// Cheap insurance against a `LiveMatchState` cached from before `stats` existed on the wire — a
// page that throws on a stale cache entry is a bad failure mode for a screen the user is
// mid-match on. Every counter 0, possession 50/50: exactly what minute 0 looks like anyway, so
// there is no separate empty state to design for.
const EMPTY_STATS: MatchStats = { home: EMPTY_TEAM_STATS, away: EMPTY_TEAM_STATS }

// Home's share of the bar. Guards the 0/0 case (every counter at minute 0) — without it,
// `home / (home + away)` is `0 / 0` -> `NaN%`, which every row would render as at kickoff.
function homeShare(home: number, away: number): number {
  const total = home + away
  return total === 0 ? 50 : (home / total) * 100
}

function StatRow({ testId, label, homeValue, awayValue, suffix, homeColor, awayColor, showDivider }: {
  testId: string
  label: string
  homeValue: number
  awayValue: number
  suffix?: string
  homeColor: string
  awayColor: string
  showDivider: boolean
}) {
  const homeWidth = homeShare(homeValue, awayValue)
  const awayWidth = 100 - homeWidth

  return (
    <Stack data-testid={testId} spacing={0.5} sx={{ py: 0.75 }}>
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Typography sx={{ minWidth: 32, fontWeight: 700 }}>{homeValue}{suffix ?? ''}</Typography>
        <Typography variant="body2" sx={{ flex: 1, textAlign: 'center', color: 'text.secondary' }}>
          {label}
        </Typography>
        <Typography sx={{ minWidth: 32, fontWeight: 700, textAlign: 'right' }}>
          {awayValue}{suffix ?? ''}
        </Typography>
      </Stack>
      <Box sx={{ display: 'flex', height: 6, borderRadius: 1, overflow: 'hidden' }}>
        <Box
          data-testid={`${testId}-bar-home`}
          sx={{
            width: `${homeWidth}%`,
            bgcolor: homeColor,
            ...(showDivider ? { borderRight: '1px solid', borderColor: 'divider' } : {}),
          }}
        />
        <Box data-testid={`${testId}-bar-away`} sx={{ width: `${awayWidth}%`, bgcolor: awayColor }} />
      </Box>
    </Stack>
  )
}

/**
 * "Estatísticas do jogo" panel — a header with the two club short names, then nine team-stat
 * rows, each with home/away values either side of a centred label and a proportional bar
 * underneath. `stats` is home/away oriented, not managed/opponent — like `MatchStats` itself
 * (api/types.ts) — so home is always the left column; this component never reorients it.
 *
 * Self-contained and deliberately not mounted anywhere yet; the nav-bar restructure task wires
 * it into `LiveMatchPage`.
 *
 * Bar colours reuse `tokenColors`/`safeKit`, the same pipeline `ClubCard` and `MatchScoreboard`
 * already use, so this panel reads as part of the same system rather than a bolt-on.
 */
export function MatchStatsTab({ stats, homeShort, awayShort, homeKit, awayKit }: {
  stats: MatchStats | null
  homeShort: string
  awayShort: string
  homeKit: LiveKitColors | null
  awayKit: LiveKitColors | null
}) {
  const s = stats ?? EMPTY_STATS
  const homeColor = tokenColors(safeKit(homeKit)).fill
  const awayColor = tokenColors(safeKit(awayKit)).fill
  // Guarded through safeKit before the RGB-distance check, same guard MatchScoreboard applies —
  // an unguarded null/non-hex primary would otherwise feed NaN into kitsClash.
  const clash = kitsClash(safeKit(homeKit).primary, safeKit(awayKit).primary)

  return (
    <Stack>
      <Stack direction="row" sx={{ justifyContent: 'space-between', px: 0.5, pb: 1 }}>
        <Typography sx={{ fontWeight: 700 }}>{homeShort}</Typography>
        <Typography sx={{ fontWeight: 700 }}>{awayShort}</Typography>
      </Stack>
      {ROWS.map(row => (
        <StatRow
          key={row.key}
          testId={`stat-${row.key}`}
          label={row.label}
          homeValue={s.home[row.key]}
          awayValue={s.away[row.key]}
          suffix={row.suffix}
          homeColor={homeColor}
          awayColor={awayColor}
          showDivider={clash}
        />
      ))}
    </Stack>
  )
}
