import { useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, InputAdornment, Link,
  Stack, TextField, Typography,
} from '@mui/material'
import { getSaveGame, getFreeAgents, getPlayer, signFreeAgent } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL, formatThousandsBR, parseMoneyInput } from '../../lib/money'
import { CARBON } from '../../muiTheme'
import { PlayerDetails } from '../squad/PlayerDetails'

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

/**
 * The free-agents list: browse and sign unattached players. Rendered inside the
 * Mercado de Transferências "Agentes livres" tab and by the standalone page below.
 * `onOpenDetails` is called with a player id when their name is clicked — the caller owns
 * fetching the full player and rendering the PlayerDetails dialog.
 */
export function FreeAgentsPanel({ onOpenDetails }: { onOpenDetails: (playerId: string) => void }) {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled, staleTime: 30_000 })
  const pool = useQuery({
    queryKey: ['freeAgents', saveGameId],
    queryFn: () => getFreeAgents(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })

  const signMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      signFreeAgent(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      // A signed player joins the managed club's roster.
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, save.data?.managedClubId ?? ''] })
    },
  })

  if (pool.isLoading) return <Typography>Carregando agentes livres…</Typography>
  if (pool.error) return <Typography color="error">Erro ao carregar agentes livres.</Typography>

  // Date-based window status from the backend (the single source of truth).
  const windowOpen = save.data?.transferWindowOpen ?? true

  return (
    <>
      {!windowOpen && <Alert role="alert" severity="error" sx={{ mb: 2 }}>Janela de transferências fechada.</Alert>}
      {signMut.isError && (
        <Alert severity="error" sx={{ mb: 2 }}>Não foi possível contratar (salário baixo ou elenco cheio).</Alert>
      )}

      {(pool.data ?? []).length === 0 && (
        <Alert severity="info">Nenhum agente livre disponível.</Alert>
      )}

      <Stack spacing={1.25}>
        {(pool.data ?? []).map(fa => {
          // Default the offer to the demand (ceil so the whole-real value still clears it),
          // displayed with pt-BR grouping so the amount is easy to read at a glance.
          const wageStr = wages[fa.playerId] ?? formatThousandsBR(Math.ceil(fa.wageDemand))
          const offeredWage = parseMoneyInput(wageStr)
          return (
            <Card key={fa.playerId} variant="outlined" sx={{ '&:hover': { borderColor: 'primary.main' } }}>
              <CardContent>
                <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}
                  sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}>
                  {/* Identity + demand data */}
                  <Box sx={{ minWidth: 0 }}>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                      <Link
                        component="button"
                        type="button"
                        underline="hover"
                        color="inherit"
                        onClick={() => onOpenDetails(fa.playerId)}
                        sx={{ font: 'inherit', fontWeight: 700, fontSize: '1.25rem', textAlign: 'left', cursor: 'pointer' }}
                      >
                        {fa.name}
                      </Link>
                      <Chip size="small" label={fa.position} sx={{ bgcolor: CARBON.bg600, color: '#E5E7EB', fontWeight: 700 }} />
                      <Chip size="small" color="primary" label={`OVR ${fa.overall}`} sx={{ fontWeight: 800 }} />
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
                      {fa.age} anos
                    </Typography>
                    <Stack direction="row" spacing={3} sx={{ mt: 1 }}>
                      <Stat label="Salário pedido" value={formatBRL(fa.wageDemand)} />
                    </Stack>
                  </Box>

                  {/* Contract form */}
                  <Stack direction="row" spacing={1} useFlexGap
                    sx={{ alignItems: 'flex-start', flexWrap: 'wrap', justifyContent: { xs: 'flex-start', md: 'flex-end' } }}>
                    <TextField
                      label="Salário"
                      size="small"
                      value={wageStr}
                      onChange={e => {
                        const n = parseMoneyInput(e.target.value)
                        setWages(w => ({ ...w, [fa.playerId]: Number.isNaN(n) ? '' : formatThousandsBR(n) }))
                      }}
                      sx={{ width: 170 }}
                      slotProps={{
                        input: { startAdornment: <InputAdornment position="start">R$</InputAdornment> },
                        htmlInput: {
                          'aria-label': `Salário para ${fa.name}`,
                          inputMode: 'numeric',
                          style: { textAlign: 'right' },
                        },
                      }}
                    />
                    <TextField
                      label="Anos"
                      type="number"
                      size="small"
                      value={years[fa.playerId] ?? '3'}
                      onChange={e => setYears(y => ({ ...y, [fa.playerId]: e.target.value }))}
                      sx={{ width: 84 }}
                      slotProps={{ htmlInput: { 'aria-label': `Anos para ${fa.name}`, min: 1, max: 5 } }}
                    />
                    <Button
                      sx={{ alignSelf: 'center' }}
                      disabled={!windowOpen || signMut.isPending}
                      onClick={() => signMut.mutate({
                        playerId: fa.playerId,
                        wage: Number.isNaN(offeredWage) ? Math.ceil(fa.wageDemand) : offeredWage,
                        contractYears: Number(years[fa.playerId] ?? 3),
                      })}
                    >
                      Contratar
                    </Button>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          )
        })}
      </Stack>
    </>
  )
}

/** Standalone page wrapper, kept for the /free-agents route. */
export function FreeAgentsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [detailsPlayerId, setDetailsPlayerId] = useState<string | null>(null)
  const detailsPlayerQuery = useQuery({
    queryKey: ['player', saveGameId, detailsPlayerId],
    queryFn: () => getPlayer(saveGameId, detailsPlayerId!, token ?? ''),
    enabled: Boolean(saveGameId && detailsPlayerId && token),
  })

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Agentes Livres</Typography>
      <FreeAgentsPanel onOpenDetails={setDetailsPlayerId} />
      <PlayerDetails
        player={detailsPlayerQuery.data ?? null}
        onClose={() => setDetailsPlayerId(null)}
        saveGameId={saveGameId}
      />
    </Box>
  )
}
