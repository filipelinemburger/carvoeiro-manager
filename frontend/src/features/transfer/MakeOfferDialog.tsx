import { useEffect, useState } from 'react'
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography,
} from '@mui/material'
import { MoneyField } from '../../components/MoneyField'
import { formatBRL, parseMoneyInput } from '../../lib/money'

/** The player a transfer offer is being drafted for, plus the backend's suggested terms. */
export type OfferTarget = {
  playerId: string
  name: string
  /** Suggested fee (the player's market value). */
  value: number
  /** Suggested weekly wage (the player's demand). */
  wageDemand: number
}

/** Parse a stored (formatted) money string, falling back to a suggested whole-real amount. */
function parsedOr(stored: string, fallback: number): number {
  const n = parseMoneyInput(stored)
  return Number.isNaN(n) ? Math.round(fallback) : n
}

/**
 * The transfer-offer form (fee / weekly wage / contract years) as a modal dialog — the same
 * fields as the market's inline proposal form, reusable from any roster view. Empty fields
 * fall back to the suggested amounts shown as helper text.
 */
export function MakeOfferDialog(props: {
  target: OfferTarget | null
  windowOpen: boolean
  busy: boolean
  onClose: () => void
  onSubmit: (v: { playerId: string; amount: number; wage: number; contractYears: number }) => void
}) {
  const { target } = props
  const [amount, setAmount] = useState('')
  const [wage, setWage] = useState('')
  const [years, setYears] = useState('3')

  // A fresh target starts a fresh form.
  useEffect(() => {
    setAmount('')
    setWage('')
    setYears('3')
  }, [target?.playerId])

  return (
    <Dialog open={Boolean(target)} onClose={props.onClose} fullWidth maxWidth="xs">
      {target && (
        <>
          <DialogTitle sx={{ fontWeight: 700 }}>Fazer proposta — {target.name}</DialogTitle>
          <DialogContent>
            {!props.windowOpen && (
              <Alert severity="error" sx={{ mb: 2 }}>Janela de transferências fechada.</Alert>
            )}
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
              Valor de mercado: {formatBRL(target.value)} · Salário pedido: {formatBRL(target.wageDemand)}
            </Typography>
            <Stack spacing={2} sx={{ mt: 0.5 }}>
              <MoneyField
                label="Valor da proposta"
                ariaLabel={`Valor para ${target.name}`}
                value={amount}
                suggested={target.value}
                onChange={setAmount}
              />
              <MoneyField
                label="Salário semanal"
                ariaLabel={`Salário para ${target.name}`}
                value={wage}
                suggested={target.wageDemand}
                onChange={setWage}
              />
              <TextField
                label="Anos"
                type="number"
                size="small"
                value={years}
                onChange={e => setYears(e.target.value)}
                sx={{ width: 84 }}
                slotProps={{ htmlInput: { 'aria-label': `Anos para ${target.name}`, min: 1, max: 5 } }}
              />
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button color="inherit" variant="outlined" onClick={props.onClose}>Cancelar</Button>
            <Button
              variant="contained"
              disabled={!props.windowOpen || props.busy}
              onClick={() => props.onSubmit({
                playerId: target.playerId,
                amount: parsedOr(amount, target.value),
                wage: parsedOr(wage, target.wageDemand),
                contractYears: Number(years || 3),
              })}
            >
              Fazer proposta
            </Button>
          </DialogActions>
        </>
      )}
    </Dialog>
  )
}
