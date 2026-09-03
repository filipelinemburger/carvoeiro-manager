import { useEffect, useState } from 'react'
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, FormControlLabel,
  NativeSelect, Stack, Switch, TextField, Typography,
} from '@mui/material'
import type { PlayerResponse, ClubSummary, LoanOfferResult } from '../../api/types'
import { refusalMessage } from '../loan/loanMessages'

/**
 * Proposes lending `player` to another club. Three outcomes come back from the same negotiation
 * round-trip, and each renders its own content instead of one blended state:
 *  - REJECTED shows why (via `refusalMessage`) and offers nothing further — a KEY_PLAYER or
 *    BELOW_LEVEL refusal is final, and even a negotiable TERMS_REJECTED isn't re-opened here
 *    (the manager closes and starts a fresh proposal instead).
 *  - COUNTERED shows the AI's counter terms with a single "Aceitar contraproposta" action.
 *  - ACCEPTED confirms the move went through.
 * Follows `TerminateDialog`'s shape: `player === null` renders nothing at all.
 */
export function LoanOutDialog({ player, clubs, pending, result, onSubmit, onAcceptCounter, onClose }: {
  player: PlayerResponse | null
  clubs: ClubSummary[]
  pending: boolean
  result: LoanOfferResult | null
  onSubmit: (v: { borrowerClubId: string; ownerWageShare: number; mayFaceOwner: boolean }) => void
  onAcceptCounter: () => void
  onClose: () => void
}) {
  const [borrowerClubId, setBorrowerClubId] = useState('')
  const [wageShare, setWageShare] = useState('50')
  const [mayFaceOwner, setMayFaceOwner] = useState(false)

  // A fresh player starts a fresh proposal — the previous player's picks never leak into the
  // next negotiation.
  useEffect(() => {
    setBorrowerClubId('')
    setWageShare('50')
    setMayFaceOwner(false)
  }, [player?.id])

  if (!player) return null

  const showForm = result === null

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Emprestar {player.name}</DialogTitle>
      <DialogContent>
        {showForm && (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <FormControl size="small" fullWidth>
              <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.25 }}>Clube de destino</Typography>
              <NativeSelect
                value={borrowerClubId}
                onChange={e => setBorrowerClubId(e.target.value)}
                inputProps={{ 'aria-label': 'Clube de destino' }}
              >
                <option value="" disabled>Selecione um clube</option>
                {clubs.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </NativeSelect>
            </FormControl>
            <TextField
              label="Parcela salarial (%)"
              helperText="Percentual do salário que seu clube continua pagando durante o empréstimo."
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
                  slotProps={{ input: { 'aria-label': 'Pode enfrentar meu clube' } }}
                />
              }
              label="Pode enfrentar meu clube"
            />
          </Stack>
        )}

        {result?.status === 'REJECTED' && (
          <Alert severity="error" sx={{ mt: 1 }}>
            {/* The backend invariant is refusal != null exactly when status is REJECTED, but the
                type doesn't say so — fall back rather than trust it blindly. */}
            {result.refusal ? refusalMessage(result.refusal) : 'Empréstimo recusado.'}
          </Alert>
        )}

        {result?.status === 'COUNTERED' && (
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <Alert severity="info">{player.name} — o clube de destino propôs uma contraproposta:</Alert>
            <Typography variant="body2">
              Parcela salarial:{' '}
              <Typography component="span" sx={{ fontWeight: 600 }}>{result.counterOwnerWageShare ?? '—'}%</Typography>
            </Typography>
            <Typography variant="body2">
              Pode enfrentar seu clube:{' '}
              <Typography component="span" sx={{ fontWeight: 600 }}>{result.counterMayFaceOwner ? 'Sim' : 'Não'}</Typography>
            </Typography>
          </Stack>
        )}

        {result?.status === 'ACCEPTED' && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Empréstimo aprovado! {player.name} já defende o novo clube.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} color="inherit">{result ? 'Fechar' : 'Cancelar'}</Button>
        {showForm && (
          <Button
            variant="contained"
            color="secondary"
            disabled={pending || !borrowerClubId}
            onClick={() => onSubmit({
              borrowerClubId,
              ownerWageShare: Number(wageShare) || 0,
              mayFaceOwner,
            })}
          >
            Propor empréstimo
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
