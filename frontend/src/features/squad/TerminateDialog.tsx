import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material'
import type { PlayerResponse } from '../../api/types'
import { formatBRL } from '../../lib/money'

/**
 * Confirms tearing up a contract. Unlike "Renovar", which fires on click, this spends money and
 * cannot be undone — so the cost, the balance it leaves, and the one-season re-signing block are
 * all stated before the manager can commit.
 */
export function TerminateDialog({ player, balance, blockedReason, pending, onConfirm, onClose }: {
  player: PlayerResponse | null
  balance: number | undefined
  blockedReason: string | null
  pending: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  if (!player) return null
  const fee = player.terminationFee ?? 0
  const newBalance = balance === undefined ? undefined : balance - fee

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Rescindir contrato de {player.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          <Typography variant="body2">
            Contrato restante: {player.contractYears} ano(s)
          </Typography>
          <Typography variant="body2">
            Multa rescisória:{' '}
            <Typography component="span" data-testid="termination-fee" sx={{ fontWeight: 600 }}>
              {formatBRL(fee)}
            </Typography>
          </Typography>
          {newBalance !== undefined && (
            <Typography variant="body2">
              Saldo após a rescisão:{' '}
              <Typography component="span" data-testid="termination-new-balance" sx={{ fontWeight: 600 }}>
                {formatBRL(newBalance)}
              </Typography>
            </Typography>
          )}
          <Alert severity="warning">
            {player.name} vira agente livre e não poderá ser recontratado por este clube até a
            próxima temporada. O elenco perde moral.
          </Alert>
          {blockedReason && <Alert severity="error">{blockedReason}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} color="inherit">Cancelar</Button>
        <Button onClick={onConfirm} color="error" variant="contained"
          disabled={pending || blockedReason !== null}>
          Confirmar rescisão
        </Button>
      </DialogActions>
    </Dialog>
  )
}
