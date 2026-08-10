import { Box, Stack, Typography } from '@mui/material'
import type { ParallelScore } from '../../api/types'

/**
 * "Resultados" — one row per other fixture in the round, moved verbatim out of `LiveMatchPage`
 * (the old Paralelas tab). `previousScores` is a snapshot of each fixture's score as of the last
 * committed render (owned by the container's `prevScoresRef` — see the comment there for why it
 * can't move down into this component), keyed by fixtureId.
 */
export function ParallelScoresList({ parallelScores, previousScores }: {
  parallelScores: ParallelScore[]
  previousScores: Record<string, string>
}) {
  return (
    <Box sx={{ mt: 1 }}>
      {parallelScores.length === 0 ? (
        <Typography sx={{ color: 'text.secondary', mt: 1 }}>Sem outros jogos nesta rodada</Typography>
      ) : (
        <Stack spacing={0.5}>
          {parallelScores.map(ps => {
            const scoreKey = `${ps.homeGoals}-${ps.awayGoals}`
            const prev = previousScores[ps.fixtureId]
            const changed = prev !== undefined && prev !== scoreKey
            return (
              <Box
                key={ps.fixtureId}
                data-testid={`parallel-${ps.fixtureId}`}
                sx={{ display: 'flex', justifyContent: 'center' }}
              >
                {/* Remounting on scoreKey change restarts the flash animation each time the
                    score moves (even on consecutive advances). Gated by `changed` so it never
                    fires on first mount or when switching to this tab. */}
                <Typography
                  key={scoreKey}
                  sx={{
                    fontFamily: 'monospace',
                    fontSize: '0.95rem',
                    px: 1.5,
                    py: 0.5,
                    borderRadius: 1,
                    ...(changed && {
                      '@keyframes parallelScoreFlash': {
                        from: { backgroundColor: 'rgba(255, 193, 7, 0.6)' },
                        to: { backgroundColor: 'transparent' },
                      },
                      animation: 'parallelScoreFlash 1s ease-out',
                    }),
                  }}
                >
                  {`${ps.homeShort} ${ps.homeGoals} x ${ps.awayGoals} ${ps.awayShort}`}
                </Typography>
              </Box>
            )
          })}
        </Stack>
      )}
    </Box>
  )
}
