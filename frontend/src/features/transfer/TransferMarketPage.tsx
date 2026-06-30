import { useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, InputAdornment,
  Paper, Stack, TextField, Typography,
} from '@mui/material'
import {
  getSaveGame, getMarket, getOffers, makeOffer, acceptCounter, withdrawOffer, respondToOffer,
} from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL, formatThousandsBR, parseMoneyInput } from '../../lib/money'

/**
 * A labeled MUI money field: an "R$" start adornment, pt-BR thousands grouping while typing,
 * and a helper line that previews the typed value (or the suggested amount when empty).
 * Parsing goes through parseMoneyInput so a stray separator can never produce NaN. The
 * `ariaLabel` stays the input's accessible name so existing queries/tests keep working.
 */
function MoneyField(props: {
  label: string
  ariaLabel: string
  value: string
  suggested: number
  onChange: (v: string) => void
}) {
  const n = parseMoneyInput(props.value)
  const helper = !Number.isNaN(n) && n > 0 ? formatBRL(n) : `sugerido ${formatBRL(props.suggested)}`
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

/** A small "LABEL / value" stat used in the player identity zone. */
function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Box>
      <Typography
        variant="caption"
        sx={{ display: 'block', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'text.secondary' }}
      >
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
    </Box>
  )
}

/** Parse a stored (formatted) money string, falling back to a suggested whole-real amount. */
function parsedOr(stored: string | undefined, fallback: number): number {
  const n = parseMoneyInput(stored ?? '')
  return Number.isNaN(n) ? Math.round(fallback) : n
}

export function TransferMarketPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [amounts, setAmounts] = useState<Record<string, string>>({})
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const market = useQuery({ queryKey: ['market', saveGameId], queryFn: () => getMarket(saveGameId, token ?? ''), enabled })
  const offers = useQuery({ queryKey: ['offers', saveGameId], queryFn: () => getOffers(saveGameId, token ?? ''), enabled })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
    // Resolving offers may clear a "window closing with pending bids" advance block.
    void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
  }
  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: invalidate,
  })
  const acceptMut = useMutation({ mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''), onSuccess: invalidate })
  const withdrawMut = useMutation({ mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''), onSuccess: invalidate })

  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onSuccess: invalidate,
  })

  if (market.isLoading) return <Typography sx={{ p: 3 }}>Carregando mercado…</Typography>
  if (market.error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar o mercado.</Typography>

  // Date-based window status from the backend (the single source of truth).
  const windowOpen = save.data?.transferWindowOpen ?? true
  const outgoing = (offers.data ?? []).filter(o => o.direction === 'OUTGOING' && o.status === 'COUNTERED')
  const incoming = (offers.data ?? []).filter(o => o.direction === 'INCOMING' && o.status === 'PENDING')
  const last = offerMut.data

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Mercado de Transferências</Typography>

      {!windowOpen && <Alert severity="error" sx={{ mb: 2 }}>Janela de transferências fechada.</Alert>}
      {last && (
        <Alert severity={last.status === 'REJECTED' ? 'error' : 'success'} sx={{ mb: 2 }}>
          {last.playerName}: {last.status === 'ACCEPTED' ? 'Proposta aceita!'
            : last.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(last.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </Alert>
      )}
      {offerMut.isError && <Alert severity="error" sx={{ mb: 2 }}>Erro ao enviar proposta.</Alert>}

      {incoming.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Propostas recebidas</Typography>
          <Stack component="ul" spacing={1} sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {incoming.map(o => (
              <Paper key={o.id} component="li" variant="outlined"
                sx={{ p: 1.25, display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 1 }}>
                <Typography sx={{ mr: 'auto', alignSelf: 'center' }}>
                  {o.playerName} — {o.fromClubName} oferece {formatBRL(o.amount)}
                </Typography>
                <Button color="secondary" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'ACCEPT', counterAmount: null })}>Aceitar venda</Button>
                <Button variant="outlined" color="inherit" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'REJECT', counterAmount: null })}>Recusar</Button>
                <MoneyField
                  label="Contraproposta"
                  ariaLabel={`Contraproposta para ${o.playerName}`}
                  value={counters[o.id] ?? ''}
                  suggested={o.amount}
                  onChange={v => setCounters(c => ({ ...c, [o.id]: v }))}
                />
                <Button disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'COUNTER', counterAmount: parsedOr(counters[o.id], o.amount) })}>Contrapor</Button>
              </Paper>
            ))}
          </Stack>
        </Box>
      )}

      {outgoing.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Contrapropostas recebidas</Typography>
          <Stack component="ul" spacing={1} sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {outgoing.map(o => (
              <Paper key={o.id} component="li" variant="outlined"
                sx={{ p: 1.25, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1 }}>
                <Typography sx={{ mr: 'auto' }}>{o.playerName} — {formatBRL(o.counterAmount ?? 0)}</Typography>
                <Button color="secondary" disabled={!windowOpen || acceptMut.isPending}
                  onClick={() => acceptMut.mutate(o.id)}>Aceitar</Button>
                <Button variant="outlined" color="inherit" disabled={withdrawMut.isPending}
                  onClick={() => withdrawMut.mutate(o.id)}>Desistir</Button>
              </Paper>
            ))}
          </Stack>
        </Box>
      )}

      <Stack spacing={1.25}>
        {(market.data ?? []).map(p => (
          <Card key={p.playerId} variant="outlined" sx={{ '&:hover': { borderColor: 'primary.main' } }}>
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}
                sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}>
                {/* Identity + market data */}
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                    <Typography variant="h6" sx={{ fontWeight: 700 }}>{p.name}</Typography>
                    <Chip size="small" label={p.position} sx={{ bgcolor: '#222', color: '#fff', fontWeight: 700 }} />
                    <Chip size="small" color="primary" label={`OVR ${p.overall}`} sx={{ fontWeight: 800 }} />
                  </Stack>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
                    <Box component="span">{p.clubName}</Box> · {p.age} anos
                  </Typography>
                  <Stack direction="row" spacing={3} sx={{ mt: 1 }}>
                    <Stat label="Valor" value={formatBRL(p.value)} />
                    <Stat label="Salário pedido" value={formatBRL(p.wageDemand)} />
                  </Stack>
                </Box>

                {/* Proposal form */}
                <Stack direction="row" spacing={1} useFlexGap
                  sx={{ alignItems: 'flex-start', flexWrap: 'wrap', justifyContent: { xs: 'flex-start', md: 'flex-end' } }}>
                  <MoneyField
                    label="Valor da proposta"
                    ariaLabel={`Valor para ${p.name}`}
                    value={amounts[p.playerId] ?? ''}
                    suggested={p.value}
                    onChange={v => setAmounts(a => ({ ...a, [p.playerId]: v }))}
                  />
                  <MoneyField
                    label="Salário semanal"
                    ariaLabel={`Salário para ${p.name}`}
                    value={wages[p.playerId] ?? ''}
                    suggested={p.wageDemand}
                    onChange={v => setWages(w => ({ ...w, [p.playerId]: v }))}
                  />
                  <TextField
                    label="Anos"
                    type="number"
                    size="small"
                    value={years[p.playerId] ?? '3'}
                    onChange={e => setYears(y => ({ ...y, [p.playerId]: e.target.value }))}
                    sx={{ width: 84 }}
                    slotProps={{ htmlInput: { 'aria-label': `Anos para ${p.name}`, min: 1, max: 5 } }}
                  />
                  <Button
                    sx={{ alignSelf: 'center' }}
                    disabled={!windowOpen || offerMut.isPending}
                    onClick={() => offerMut.mutate({
                      playerId: p.playerId,
                      amount: parsedOr(amounts[p.playerId], p.value),
                      wage: parsedOr(wages[p.playerId], p.wageDemand),
                      contractYears: Number(years[p.playerId] ?? 3),
                    })}
                  >
                    Propor
                  </Button>
                </Stack>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>
    </Box>
  )
}
