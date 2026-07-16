import { Box, Stack, Typography } from '@mui/material'
import type { PlayerRating } from '../../api/types'

export const POSITION_LABEL: Record<string, string> = { GK: 'GOL', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }

/** Colour band for a 0–10 grade: green (great) → amber (average) → red (poor). */
export function ratingColor(rating: number): string {
  if (rating >= 7.5) return '#1b7e3c'
  if (rating >= 6.5) return '#3e8e41'
  if (rating >= 5.5) return '#c9911f'
  return '#c0392b'
}

/**
 * Presentational list of players' 0–10 match grades, best first. Shared by the live full-time
 * screen and the persisted post-match news summary so both render identically. When
 * `onPitchIds` is provided, any rated player NOT in that set (subbed off, or sent off) renders
 * dimmed — omit it (as the persisted news card does) to render every row at full opacity.
 */
export function PlayerRatingsList({ ratings, onPitchIds }: { ratings: PlayerRating[]; onPitchIds?: Set<string> }) {
  const sorted = [...ratings].sort((a, b) => b.rating - a.rating)
  return (
    <Stack spacing={0.5} sx={{ mt: 0.5 }}>
      {sorted.map(r => {
        const subbedOff = onPitchIds !== undefined && !onPitchIds.has(r.playerId)
        return (
          <Stack
            key={r.playerId}
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center' }}
            style={subbedOff ? { opacity: 0.5 } : undefined}
          >
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
            {subbedOff && (
              <Typography component="span" title="Substituído" aria-label="Substituído" sx={{ color: 'text.secondary' }}>
                ↩
              </Typography>
            )}
            {r.goals > 0 && (
              <Typography component="span" aria-label={`${r.goals} gol(s)`}>
                {'⚽'.repeat(Math.min(r.goals, 3))}
              </Typography>
            )}
            <Typography sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>{r.minutesPlayed}'</Typography>
          </Stack>
        )
      })}
    </Stack>
  )
}
