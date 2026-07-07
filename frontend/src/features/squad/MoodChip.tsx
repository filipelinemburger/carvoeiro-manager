import { Chip, Typography } from '@mui/material'
import { moraleMood } from './morale'

/**
 * Shows a morale value's mood. By default a small coloured chip (emoji + label);
 * with `emojiOnly` just the bare emoji, no label and no background — for dense lists.
 */
export function MoodChip({ morale, prefix = '', emojiOnly = false }: { morale: number; prefix?: string; emojiOnly?: boolean }) {
  const mood = moraleMood(morale)
  if (emojiOnly) {
    return (
      <Typography component="span" role="img" aria-label={mood.label} title={mood.label} sx={{ fontSize: '1.15rem', lineHeight: 1 }}>
        {mood.emoji}
      </Typography>
    )
  }
  return (
    <Chip
      size="small"
      label={`${prefix}${mood.emoji} ${mood.label}`}
      sx={{ bgcolor: mood.color, color: '#fff', fontWeight: 600 }}
    />
  )
}
