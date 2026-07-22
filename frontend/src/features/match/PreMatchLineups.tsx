import { Box, Link, Stack, Typography } from '@mui/material'
import type { LivePlayer } from '../../api/types'
import { POSITION_LABEL } from './PlayerRatingsList'

/** `isOwnSide` marks whether this column is the managed club's XI (vs the AI opponent's) —
 * used to decide `isOwnPlayer` when a name in this column is clicked. */
type LineupSide = { clubName: string; formation: string; players: LivePlayer[]; isOwnSide: boolean }

type OnPlayerClick = (playerId: string, isOwnPlayer: boolean) => void

function LineupColumn({ side, onPlayerClick }: { side: LineupSide; onPlayerClick?: OnPlayerClick }) {
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
            {onPlayerClick ? (
              <Link
                component="button"
                type="button"
                underline="hover"
                color="inherit"
                onClick={() => onPlayerClick(p.playerId, side.isOwnSide)}
                sx={{
                  flex: 1, minWidth: 0, font: 'inherit', textAlign: 'left', cursor: 'pointer',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}
              >
                {p.name}
              </Link>
            ) : (
              <Typography sx={{ flex: 1, minWidth: 0 }} noWrap>{p.name}</Typography>
            )}
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
 *
 * `onPlayerClick`, when provided, turns each name into a button that opens the read-only
 * player details modal (owned by the caller) — passed the player's id and whether they belong
 * to the managed side (`side.isOwnSide`).
 */
export function PreMatchLineups({ home, away, onPlayerClick }: {
  home: LineupSide
  away: LineupSide
  onPlayerClick?: OnPlayerClick
}) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
      <LineupColumn side={home} onPlayerClick={onPlayerClick} />
      <LineupColumn side={away} onPlayerClick={onPlayerClick} />
    </Stack>
  )
}
