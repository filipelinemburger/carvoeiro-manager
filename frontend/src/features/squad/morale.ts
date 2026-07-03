export type Mood = { emoji: string; label: string; color: string }

/** Maps a [-5..+5] morale value to a display mood (emoji + pt-BR label + colour). */
export function moraleMood(morale: number): Mood {
  if (morale <= -3) return { emoji: '😞', label: 'Desanimado', color: '#c0392b' }
  if (morale <= -1) return { emoji: '😕', label: 'Descontente', color: '#c9911f' }
  if (morale === 0) return { emoji: '😐', label: 'Neutro', color: '#7f8c8d' }
  if (morale <= 2) return { emoji: '🙂', label: 'Contente', color: '#3e8e41' }
  return { emoji: '🤩', label: 'Radiante', color: '#1b7e3c' }
}

/** Squad-average morale, rounded to the nearest whole for bucket lookup; 0 for an empty squad. */
export function teamMorale(morales: number[]): number {
  if (morales.length === 0) return 0
  return Math.round(morales.reduce((sum, m) => sum + m, 0) / morales.length)
}
