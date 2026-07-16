import { Box, Stack, Typography } from '@mui/material'
import type { LivePlayer } from '../../api/types'
import { POSITION_LABEL } from './PlayerRatingsList'

type LineupSide = { clubName: string; formation: string; players: LivePlayer[] }

function LineupColumn({ side }: { side: LineupSide }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography sx={{ fontWeight: 700 }}>{side.clubName}</Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>{side.formation}</Typography>
      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {side.players.map(p => (
          <Stack key={p.playerId} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography sx={{ minWidth: 30, color: 'text.secondary', fontSize: '0.8rem' }}>
              {POSITION_LABEL[p.position] ?? p.position}
            </Typography>
            <Typography sx={{ flex: 1, minWidth: 0 }} noWrap>{p.name}</Typography>
            <Typography sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>{p.overall}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}

/**
 * Both starting XIs side by side — shown in the Lances tab at kickoff (minute 0), before any
 * events exist to fill that feed. Stacks vertically on mobile, matching the Atuações tab.
 */
export function PreMatchLineups({ home, away }: { home: LineupSide; away: LineupSide }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
      <LineupColumn side={home} />
      <LineupColumn side={away} />
    </Stack>
  )
}
