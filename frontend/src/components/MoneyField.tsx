import { InputAdornment, TextField } from '@mui/material'
import { formatBRL, formatThousandsBR, parseMoneyInput } from '../lib/money'

/**
 * A labeled MUI money field: an "R$" start adornment, pt-BR thousands grouping while typing,
 * and a helper line that previews the typed value (or the suggested amount when empty).
 * Parsing goes through parseMoneyInput so a stray separator can never produce NaN. The
 * `ariaLabel` stays the input's accessible name so existing queries/tests keep working.
 */
export function MoneyField(props: {
  label: string
  ariaLabel: string
  value: string
  suggested: number
  onChange: (v: string) => void
}) {
  const n = parseMoneyInput(props.value)
  const helper = !Number.isNaN(n) && n > 0 ? formatBRL(n) : undefined
  return (
    <TextField
      label={props.label}
      size="small"
      value={props.value}
      onChange={e => {
        const parsed = parseMoneyInput(e.target.value)
        props.onChange(Number.isNaN(parsed) ? '' : formatThousandsBR(parsed))
      }}
      helperText={helper}
      sx={{ width: 170 }}
      slotProps={{
        input: { startAdornment: <InputAdornment position="start">R$</InputAdornment> },
        htmlInput: { 'aria-label': props.ariaLabel, inputMode: 'numeric', style: { textAlign: 'right' } },
      }}
    />
  )
}
