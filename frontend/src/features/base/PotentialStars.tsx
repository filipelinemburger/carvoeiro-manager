import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import { Box } from '@mui/material'

/** Read-only 1–5 star potential band. Never renders the raw potential ceiling. */
export function PotentialStars({ stars }: { stars: number }) {
  const clamped = Math.max(0, Math.min(5, stars))
  return (
    <Box
      component="span"
      aria-label={`Potencial ${clamped} de 5`}
      title={`Potencial ${clamped}/5`}
      sx={{ display: 'inline-flex', color: 'warning.main', verticalAlign: 'middle' }}
    >
      {Array.from({ length: 5 }, (_, i) =>
        i < clamped ? (
          <StarIcon key={i} fontSize="small" />
        ) : (
          <StarBorderIcon key={i} fontSize="small" />
        ),
      )}
    </Box>
  )
}
