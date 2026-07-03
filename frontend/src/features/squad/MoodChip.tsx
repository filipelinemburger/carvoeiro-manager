import { Chip } from '@mui/material'
import { moraleMood } from './morale'

/** A small coloured chip showing a morale value's mood (emoji + label). */
export function MoodChip({ morale, prefix = '' }: { morale: number; prefix?: string }) {
  const mood = moraleMood(morale)
  return (
    <Chip
      size="small"
      label={`${prefix}${mood.emoji} ${mood.label}`}
      sx={{ bgcolor: mood.color, color: '#fff', fontWeight: 600 }}
    />
  )
}
