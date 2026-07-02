import { useState } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, List, ListItemButton, ListItemText, Typography, Box,
} from '@mui/material'
import type { LivePlayer } from '../../api/types'

interface SubDialogProps {
  open: boolean
  onClose: () => void
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  pending: boolean
  onConfirm: (outPlayerId: string, inPlayerId: string) => void
}

export function SubDialog({ open, onClose, onPitch, bench, subsRemaining, pending, onConfirm }: SubDialogProps) {
  const [out, setOut] = useState<string | null>(null)
  const [inp, setInp] = useState<string | null>(null)
  const available = bench.filter(b => !b.used && b.eligible)

  return (
    <Dialog open={open} onClose={onClose} fullWidth>
      <DialogTitle>Substituição — restam {subsRemaining}</DialogTitle>
      <DialogContent>
        <Typography variant="subtitle2" sx={{ mt: 1 }}>Quem sai</Typography>
        <List dense>
          {onPitch.map(p => (
            <ListItemButton key={p.playerId} selected={out === p.playerId} onClick={() => setOut(p.playerId)}>
              <ListItemText primary={`${p.name}${p.hobbled ? ' 🚑' : ''}`} secondary={`${p.position} · Condição ${p.fitness}%`} />
            </ListItemButton>
          ))}
        </List>
        <Typography variant="subtitle2" sx={{ mt: 1 }}>Quem entra</Typography>
        <List dense>
          {available.map(p => (
            <ListItemButton key={p.playerId} selected={inp === p.playerId} onClick={() => setInp(p.playerId)}>
              <ListItemText primary={p.name} secondary={`${p.position} · Condição ${p.fitness}%`} />
            </ListItemButton>
          ))}
          {available.length === 0 && (
            <Box sx={{ px: 2, py: 1 }}><Typography variant="body2">Sem reservas disponíveis.</Typography></Box>
          )}
        </List>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button variant="contained" disabled={!out || !inp || pending || subsRemaining <= 0}
          onClick={() => { if (out && inp) onConfirm(out, inp) }}>
          Confirmar
        </Button>
      </DialogActions>
    </Dialog>
  )
}
