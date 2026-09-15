import { useEffect, useState } from 'react'
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Stack,
  Switch, TextField, Typography,
} from '@mui/material'
import type { LoanOfferResult } from '../../api/types'
import { refusalMessage, isNegotiable } from '../loan/loanMessages'

/**
 * Proposes borrowing `player` from whichever club currently owns him — the mirror of
 * `LoanOutDialog`, minus the club picker (there's nothing to pick: the owner is whoever
 * holds the player). Opened from the market row and from a rival club's roster, so it takes
 * only the identity both have. Same three outcomes, same reasoning:
 *  - REJECTED shows why (via `refusalMessage`) and offers nothing further — even a negotiable
 *    TERMS_REJECTED isn't re-opened here (the manager closes and sends a fresh proposal
 *    instead). The alert's severity alone (warning vs. error, via `isNegotiable`) tells the
 *    manager whether trying again could work, without adding a second path to resubmit.
 *  - COUNTERED shows the owner's counter terms with a single "Aceitar contraproposta" action.
 *  - ACCEPTED confirms the player now plays for the manager's own club.
 * Follows `LoanOutDialog`'s shape: `player === null` renders nothing at all.
 */
export function LoanInDialog({ player, pending, result, onSubmit, onAcceptCounter, onClose }: {
  player: { playerId: string; name: string } | null
  pending: boolean
  result: LoanOfferResult | null
  onSubmit: (v: { ownerWageShare: number; mayFaceOwner: boolean }) => void
  onAcceptCounter: () => void
  onClose: () => void
}) {
  const [wageShare, setWageShare] = useState('50')
  const [mayFaceOwner, setMayFaceOwner] = useState(false)

  // A fresh player starts a fresh proposal — the previous player's picks never leak into the
  // next negotiation.
  useEffect(() => {
    setWageShare('50')
    setMayFaceOwner(false)
  }, [player?.playerId])

  if (!player) return null

  const showForm = result === null

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Pedir empréstimo de {player.name}</DialogTitle>
      <DialogContent>
        {showForm && (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Parcela salarial (%)"
              helperText="Percentual do salário que o clube dono continua pagando durante o empréstimo."
              type="number"
              size="small"
              value={wageShare}
              onChange={e => setWageShare(e.target.value)}
              slotProps={{ htmlInput: { 'aria-label': 'Parcela salarial (%)', min: 0, max: 100 } }}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={mayFaceOwner}
                  onChange={e => setMayFaceOwner(e.target.checked)}
                  slotProps={{ input: { 'aria-label': 'Pode enfrentar o clube dono' } }}
                />
              }
              label="Pode enfrentar o clube dono"
            />
          </Stack>
        )}

        {result?.status === 'REJECTED' && (
          <Alert severity={result.refusal && isNegotiable(result.refusal) ? 'warning' : 'error'} sx={{ mt: 1 }}>
            {/* The backend invariant is refusal != null exactly when status is REJECTED, but the
                type doesn't say so — fall back rather than trust it blindly. */}
            {result.refusal ? refusalMessage(result.refusal) : 'Empréstimo recusado.'}
          </Alert>
        )}

        {result?.status === 'COUNTERED' && (
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <Alert severity="info">{player.name} — o clube dono propôs uma contraproposta:</Alert>
            <Typography variant="body2">
              Parcela salarial:{' '}
              <Typography component="span" sx={{ fontWeight: 600 }}>{result.counterOwnerWageShare ?? '—'}%</Typography>
            </Typography>
            <Typography variant="body2">
              Pode enfrentar o clube dono:{' '}
              <Typography component="span" sx={{ fontWeight: 600 }}>{result.counterMayFaceOwner ? 'Sim' : 'Não'}</Typography>
            </Typography>
          </Stack>
        )}

        {result?.status === 'ACCEPTED' && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Empréstimo aprovado! {player.name} já defende seu clube.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} color="inherit">{result ? 'Fechar' : 'Cancelar'}</Button>
        {showForm && (
          <Button
            variant="contained"
            color="secondary"
            disabled={pending}
            onClick={() => onSubmit({
              ownerWageShare: Number(wageShare) || 0,
              mayFaceOwner,
            })}
          >
            Pedir empréstimo
          </Button>
        )}
        {result?.status === 'COUNTERED' && (
          <Button variant="contained" color="secondary" disabled={pending} onClick={onAcceptCounter}>
            Aceitar contraproposta
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
