import {
  Dialog, DialogTitle, DialogContent, IconButton, List, ListItemButton, ListItemText,
  Box, Chip, Stack, Typography,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import type { PlayerResponse, DetailedPosition } from '../../api/types'
import type { Slot } from '../pitch/pitchSlots'
import { DETAILED_POSITION_SHORT, isNatural } from '../players/positionLabels'
import { ovrBadgeSx } from '../../muiTheme'

/** The player's natural detailed positions (primary + alternates), short-labeled and joined. */
function naturalPositionsLabel(p: PlayerResponse): string {
  const positions = [p.detailedPosition, ...(p.altPositions ?? [])].filter(
    (x): x is DetailedPosition => x != null,
  )
  return positions.map(pos => DETAILED_POSITION_SHORT[pos]).join(' · ')
}

/**
 * One candidate row: name, OVR badge, an "Atual" chip on the current occupant, and — for a
 * player who isn't natural for the slot — an "Improvisado" warning chip naming their real
 * position(s). Clicking anywhere on the row picks that player for the slot.
 */
function PlayerRow({ player, slot, isCurrent, onPick }: {
  player: PlayerResponse
  slot: Slot
  isCurrent: boolean
  onPick: (player: PlayerResponse) => void
}) {
  const natural = isNatural(player, slot.accepted)
  const naturalLabel = naturalPositionsLabel(player)
  return (
    <ListItemButton
      data-testid={`slot-picker-row-${player.id}`}
      data-natural={natural ? 'true' : 'false'}
      selected={isCurrent}
      onClick={() => onPick(player)}
      sx={{ borderRadius: 1, mb: 0.5 }}
    >
      <ListItemText
        primary={
          <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography component="span" sx={{ fontWeight: 600 }}>{player.name}</Typography>
            <Box
              component="span"
              sx={{ display: 'inline-block', minWidth: 26, px: 0.75, py: 0.1, borderRadius: 1, fontSize: '0.72rem', ...ovrBadgeSx(player.overall) }}
            >
              {player.overall}
            </Box>
            {isCurrent && <Chip size="small" color="secondary" label="Atual" />}
            {!natural && (
              <Chip
                size="small"
                color="warning"
                label="Improvisado"
                title={`Improvisado — posição natural: ${naturalLabel || '—'}`}
              />
            )}
          </Stack>
        }
        secondary={!natural && naturalLabel ? naturalLabel : undefined}
      />
    </ListItemButton>
  )
}

/**
 * Opened by tapping a pitch slot (spec §5.1/§5.2). Lists the whole eligible squad — GK-position
 * players are excluded from an outfield slot's list (the backend allows at most 1 GK in the XI,
 * so an improvised keeper outfield would always 422) — natural fits for the slot first (by
 * overall desc), then everyone else flagged "Improvisado" with their real position(s). The
 * current occupant (if any) is highlighted. Picking a player is reported via `onPick`; the caller
 * decides the swap-vs-place logic and closes the dialog.
 */
export function SlotPickerDialog({ open, onClose, slot, players, currentOccupantId, onPick }: {
  open: boolean
  onClose: () => void
  /** The slot being filled; ignored while `open` is false (may be null then). */
  slot: Slot | null
  /** Whole squad to choose from — already filtered to eligible players by the caller. */
  players: PlayerResponse[]
  currentOccupantId?: string
  onPick: (player: PlayerResponse) => void
}) {
  const isGkSlot = Boolean(slot?.accepted.includes('GOLEIRO'))
  const candidates = slot ? players.filter(p => isGkSlot || p.position !== 'GK') : []
  const naturals = slot ? candidates.filter(p => isNatural(p, slot.accepted)).sort((a, b) => b.overall - a.overall) : []
  const improvised = slot ? candidates.filter(p => !isNatural(p, slot.accepted)).sort((a, b) => b.overall - a.overall) : []
  const slotShort = slot ? DETAILED_POSITION_SHORT[slot.accepted[0]] : ''

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      {slot && (
        <>
          <DialogTitle sx={{ pr: 6 }}>
            {`Quem joga aqui? (${slotShort})`}
            <IconButton aria-label="Fechar" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}>
              <CloseIcon />
            </IconButton>
          </DialogTitle>
          <DialogContent dividers>
            {candidates.length === 0 ? (
              <Typography sx={{ color: 'text.secondary' }}>Nenhum jogador disponível.</Typography>
            ) : (
              <List dense disablePadding>
                {[...naturals, ...improvised].map(p => (
                  <PlayerRow key={p.id} player={p} slot={slot} isCurrent={p.id === currentOccupantId} onPick={onPick} />
                ))}
              </List>
            )}
          </DialogContent>
        </>
      )}
    </Dialog>
  )
}
