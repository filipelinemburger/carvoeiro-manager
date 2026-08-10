import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material'
import { Pitch } from '../pitch/Pitch'
import { buildTeam } from './LineupsTab'
import { LineupColumn } from './LineupColumn'
import type { SideView } from './matchSides'
import type { LiveTactics, Mentality, GameStyle, AttackZone, Pressing, Marking } from '../../api/types'
import {
  MENTALITY_LABEL, GAME_STYLE_LABEL, ATTACK_ZONE_LABEL, PRESSING_LABEL, MARKING_LABEL,
} from '../squad/tacticsLabels'

/**
 * One tactics row, label left / value right, rendered as plain text — never a disabled `Select`.
 * A disabled form control reads as "you could change this but not now", which is the wrong
 * message when scouting an AI-controlled opponent: nothing here is ever editable.
 */
function TacticRow({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" spacing={2} sx={{ justifyContent: 'space-between' }}>
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>{label}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>{value}</Typography>
    </Stack>
  )
}

/**
 * Read-only opponent scouting panel, opened from the opponent's club card on the live match
 * header. Shows the opponent's XI on a pitch, their tactical settings as plain text, and their
 * XI as a list — no apply action, no mutation, just "Fechar".
 *
 * `side` must come from `sidesOf` (`matchSides.ts`) — the caller resolves `managedIsHome ?
 * sides.away : sides.home` — never a hand-rolled home/away mapping, so this panel can never
 * accidentally surface the managed club's own lineup.
 */
export function OpponentScoutingPanel({ open, onClose, side, tactics, onPlayerClick }: {
  open: boolean
  onClose: () => void
  side: SideView
  tactics: LiveTactics
  onPlayerClick?: (playerId: string, isOwnPlayer: boolean) => void
}) {
  const team = buildTeam(side.players, side.formation, side.kit)

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{side.shortName}</DialogTitle>
      <DialogContent>
        <Box sx={{ width: { xs: '100%', sm: 280 }, mx: 'auto' }}>
          {/* No `away` prop — Pitch renders a single team fine, and there is nothing to swap
              players into here anyway (onSwap/onSlotClick both omitted: read-only). */}
          <Pitch home={team} />
        </Box>

        <Stack spacing={1} data-testid="opponent-tactics" sx={{ my: 2 }}>
          <TacticRow label="Formação" value={tactics.formation} />
          <TacticRow label="Mentalidade" value={MENTALITY_LABEL[tactics.mentality as Mentality] ?? tactics.mentality} />
          <TacticRow label="Estilo de jogo" value={GAME_STYLE_LABEL[tactics.gameStyle as GameStyle] ?? tactics.gameStyle} />
          <TacticRow label="Onde forçar o jogo" value={ATTACK_ZONE_LABEL[tactics.attackZone as AttackZone] ?? tactics.attackZone} />
          <TacticRow label="Pressão" value={PRESSING_LABEL[tactics.pressing as Pressing] ?? tactics.pressing} />
          <TacticRow label="Marcação" value={MARKING_LABEL[tactics.marking as Marking] ?? tactics.marking} />
        </Stack>

        <LineupColumn side={side} onPlayerClick={onPlayerClick} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Fechar</Button>
      </DialogActions>
    </Dialog>
  )
}
