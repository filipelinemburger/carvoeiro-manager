import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Card, CardContent, Divider, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getFinances } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { formatBRL } from '../../lib/money'
import type { FinanceTransaction } from '../../api/types'

const TYPE_LABELS: Record<string, string> = {
  GATE: 'Bilheteria',
  MATCH_COST: 'Custo da partida',
  WAGES: 'Salários',
  SPONSOR: 'Patrocínio',
  UPKEEP: 'Manutenção',
  PRIZE: 'Premiação',
  TRANSFER_IN: 'Venda de jogador',
  TRANSFER_OUT: 'Compra de jogador',
}

/** Returns a YYYY-MM key for grouping: uses txDate if available, falls back to seasonYear/round. */
function monthKey(t: FinanceTransaction): string {
  if (t.txDate) {
    // txDate is 'YYYY-MM-DD' — take the first 7 chars
    return t.txDate.substring(0, 7)
  }
  // Legacy rows without txDate: group by seasonYear (rough fallback)
  return `${t.seasonYear}-??`
}

function monthLabel(key: string): string {
  if (key.includes('??')) return key
  const [year, month] = key.split('-')
  const date = new Date(Number(year), Number(month) - 1, 1)
  return date.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' })
}

/** A single labeled value pair used in the mobile stacked-card layout. */
function Field({ label, value, valueColor }: { label: string; value: ReactNode; valueColor?: string }) {
  return (
    <Stack direction="row" spacing={2} sx={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
      <Typography variant="caption" sx={{ textTransform: 'uppercase', letterSpacing: '0.04em', color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 600, textAlign: 'right', color: valueColor, fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Typography>
    </Stack>
  )
}

export function FinancesPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const isMobile = useIsMobile()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['finances', saveGameId, clubId],
    queryFn: () => getFinances(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  if (isLoading) return <Typography sx={{ p: 3 }}>Carregando finanças…</Typography>
  if (error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar finanças.</Typography>

  const balance = data?.balance ?? 0
  const transactions = data?.transactions ?? []
  const negative = balance < 0

  // Group transactions by YYYY-MM (or season fallback), preserving insertion order
  const grouped = new Map<string, FinanceTransaction[]>()
  for (const t of transactions) {
    const key = monthKey(t)
    const group = grouped.get(key)
    if (group) {
      group.push(t)
    } else {
      grouped.set(key, [t])
    }
  }

  return (
    <Box sx={{ p: { xs: 2, sm: 3 } }}>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700, mb: 2 }}>Finanças</Typography>
      <Typography
        sx={{ fontSize: '1.25rem', fontWeight: 'bold', color: negative ? 'red' : undefined }}
      >
        Saldo: {formatBRL(balance)}
      </Typography>
      {negative && (
        <Alert role="alert" severity="error" sx={{ mt: 1, mb: 1 }}>
          Atenção: o clube está com saldo negativo.
        </Alert>
      )}

      {grouped.size === 0 && <Typography sx={{ mt: 2 }}>Nenhuma transação ainda.</Typography>}

      {Array.from(grouped.entries()).map(([key, rows]) => {
        const monthNet = rows.reduce((sum, t) => sum + t.amount, 0)
        return (
          <Box component="section" key={key} sx={{ mt: 3 }}>
            <Typography
              variant="h6"
              component="h3"
              sx={{ textTransform: 'capitalize', mb: 1 }}
            >
              {monthLabel(key)}
              <Box
                component="span"
                sx={{ fontWeight: 'normal', fontSize: '0.9rem', ml: 2, color: monthNet >= 0 ? 'green' : 'red' }}
              >
                ({monthNet >= 0 ? '+' : ''}{formatBRL(monthNet)})
              </Box>
            </Typography>
            <Divider sx={{ mb: 1 }} />

            {isMobile ? (
              <Stack spacing={1}>
                {rows.map((t, i) => (
                  <Card key={`${t.round}-${t.type}-${i}`} variant="outlined">
                    <CardContent>
                      <Stack spacing={0.5}>
                        <Field label="Rodada" value={t.round} />
                        <Field label="Tipo" value={TYPE_LABELS[t.type] ?? t.type} />
                        <Field label="Descrição" value={t.description} />
                        <Field label="Valor" value={formatBRL(t.amount)} valueColor={t.amount < 0 ? 'red' : 'green'} />
                      </Stack>
                    </CardContent>
                  </Card>
                ))}
              </Stack>
            ) : (
              <TableContainer component={Paper} variant="outlined">
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell align="center">Rodada</TableCell>
                      <TableCell align="left">Tipo</TableCell>
                      <TableCell align="left">Descrição</TableCell>
                      <TableCell align="right">Valor</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((t, i) => (
                      <TableRow key={`${t.round}-${t.type}-${i}`}>
                        <TableCell align="center">{t.round}</TableCell>
                        <TableCell align="left">{TYPE_LABELS[t.type] ?? t.type}</TableCell>
                        <TableCell align="left">{t.description}</TableCell>
                        <TableCell align="right" sx={{ color: t.amount < 0 ? 'red' : 'green' }}>
                          {formatBRL(t.amount)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Box>
        )
      })}
    </Box>
  )
}
