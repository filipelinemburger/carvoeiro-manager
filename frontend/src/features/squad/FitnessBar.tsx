import { Box, LinearProgress, Typography } from '@mui/material'

export type FitnessSeverity = 'error' | 'warning' | 'success'

export function fitnessSeverity(fitness: number): FitnessSeverity {
  if (fitness < 40) return 'error'
  if (fitness < 70) return 'warning'
  return 'success'
}

export function FitnessBar({ fitness }: { fitness: number }) {
  const clamped = Math.max(0, Math.min(100, fitness))
  const severity = fitnessSeverity(clamped)
  return (
    <Box
      aria-label={`Condição ${clamped}%`}
      data-severity={severity}
      sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 72 }}
    >
      <LinearProgress
        variant="determinate"
        value={clamped}
        color={severity}
        sx={{ flex: 1, height: 6, borderRadius: 3 }}
      />
      <Typography variant="caption" sx={{ minWidth: 30, textAlign: 'right' }}>{clamped}%</Typography>
    </Box>
  )
}
