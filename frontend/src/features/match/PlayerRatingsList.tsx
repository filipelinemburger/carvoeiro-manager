import { Box, Stack, Typography } from '@mui/material'
import type { PlayerRating } from '../../api/types'

const POSITION_LABEL: Record<string, string> = { GK: 'GOL', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }

/** Colour band for a 0–10 grade: green (great) → amber (average) → red (poor). */
export function ratingColor(rating: number): string {
  if (rating >= 7.5) return '#1b7e3c'
  if (rating >= 6.5) return '#3e8e41'
  if (rating >= 5.5) return '#c9911f'
  return '#c0392b'
}

/**
 * Presentational list of managed players' 0–10 match grades, best first. Shared by the live
 * full-time screen and the persisted post-match news summary so both render identically.
 */
export function PlayerRatingsList({ ratings }: { ratings: PlayerRating[] }) {
  const sorted = [...ratings].sort((a, b) => b.rating - a.rating)
  return (
    <Stack spacing={0.5} sx={{ mt: 0.5 }}>
      {sorted.map(r => (
        <Stack key={r.playerId} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <Box
            sx={{
              minWidth: 36, textAlign: 'center', fontWeight: 700, color: '#fff',
              bgcolor: ratingColor(r.rating), borderRadius: 1, px: 0.5, py: '2px', fontSize: '0.85rem',
            }}
          >
            {r.rating.toFixed(1)}
          </Box>
          <Typography sx={{ minWidth: 30, color: 'text.secondary', fontSize: '0.8rem' }}>
            {POSITION_LABEL[r.position] ?? r.position}
          </Typography>
          <Typography sx={{ flex: 1, minWidth: 0 }} noWrap>{r.name}</Typography>
          {r.goals > 0 && (
            <Typography component="span" aria-label={`${r.goals} gol(s)`}>
              {'⚽'.repeat(Math.min(r.goals, 3))}
            </Typography>
          )}
          <Typography sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>{r.minutesPlayed}'</Typography>
        </Stack>
      ))}
    </Stack>
  )
}
