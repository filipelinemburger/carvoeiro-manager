import type { ReactNode } from 'react'
import { Menu, Box, Typography } from '@mui/material'

/** Which player the menu is open for, and where the pointer was when it opened. */
export type RoleMenuAnchor = { playerId: string; x: number; y: number }

/**
 * The Função/Foco editor for a starter, opened by right-clicking (or long-pressing) their token
 * on the pitch. Replaces the two columns the Plantel table used to carry: those only ever
 * populated for starters, which is exactly the set of players visible on the pitch.
 *
 * The controls are passed in rather than built here — they close over SquadPage's role/focus
 * state and its slot-derived position rules, which have no business being duplicated.
 * The menu deliberately does NOT close on change, so both fields can be set in one open.
 */
export function RoleContextMenu({ anchor, playerName, funcaoControl, focoControl, onClose }: {
  anchor: RoleMenuAnchor | null
  playerName: string
  funcaoControl: ReactNode
  focoControl: ReactNode
  onClose: () => void
}) {
  return (
    <Menu
      open={anchor !== null}
      onClose={onClose}
      anchorReference="anchorPosition"
      anchorPosition={anchor ? { top: anchor.y, left: anchor.x } : undefined}
    >
      <Box sx={{ px: 2, py: 1, minWidth: 220 }}>
        <Typography sx={{ fontWeight: 700, mb: 1 }}>{playerName}</Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Função</Typography>
        <Box sx={{ mb: 1.5 }}>{funcaoControl}</Box>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Foco</Typography>
        <Box>{focoControl}</Box>
      </Box>
    </Menu>
  )
}
