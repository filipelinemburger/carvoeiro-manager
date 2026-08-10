import { useState } from 'react'
import { Box, Button, Menu, MenuItem } from '@mui/material'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import type { LiveMatchStatus } from '../../api/types'

/**
 * Bottom-right menu holding the two "skip ahead" actions. `onSkip` is the container's `skipTo`,
 * which carries its own in-flight guard at the source (see LiveMatchPage.tsx) — this component
 * never reimplements that guard, `actionPending` only drives the items' `disabled` prop.
 *
 * The trigger button itself is hidden entirely at full time — there is nothing left to skip to.
 *
 * `onOpenChange`, when provided, fires whenever the menu opens or closes. `LiveMatchPage` uses it
 * to gate its per-minute clock: the menu is a backdrop-covering surface just like the actions
 * dialog and the scouting panel, and `actionPending` flips these items enabled/disabled every
 * tick, so the clock must not keep ticking behind it while it is open.
 */
export function GameOptionsMenu({ minute, status, actionPending, onSkip, onOpenChange }: {
  minute: number
  status: LiveMatchStatus
  actionPending: boolean
  onSkip: (target: 'HALF_TIME' | 'FULL_TIME') => void
  onOpenChange?: (open: boolean) => void
}) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null)
  const open = Boolean(anchorEl)

  const halftime = status === 'HALF_TIME'
  const fullTime = status === 'FULL_TIME'

  if (fullTime) return null

  const closeMenu = () => {
    setAnchorEl(null)
    onOpenChange?.(false)
  }

  const handleSkip = (target: 'HALF_TIME' | 'FULL_TIME') => {
    onSkip(target)
    closeMenu()
  }

  return (
    <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
      <Button
        variant="outlined"
        endIcon={<MoreVertIcon />}
        aria-expanded={open}
        onClick={(e) => { setAnchorEl(e.currentTarget); onOpenChange?.(true) }}
      >
        Opções do jogo
      </Button>
      <Menu anchorEl={anchorEl} open={open} onClose={closeMenu}>
        {!halftime && minute < 45 && (
          <MenuItem disabled={actionPending} onClick={() => handleSkip('HALF_TIME')}>
            Avançar até o intervalo
          </MenuItem>
        )}
        <MenuItem disabled={actionPending} onClick={() => handleSkip('FULL_TIME')}>
          Avançar até o fim
        </MenuItem>
      </Menu>
    </Box>
  )
}
