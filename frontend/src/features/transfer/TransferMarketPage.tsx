import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Divider, FormControl, Link,
  NativeSelect, Pagination, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material'
import {
  getSaveGame, getMarket, getClubs, getOffers, getSquad, makeOffer, acceptCounter, withdrawOffer, respondToOffer,
} from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { FreeAgentsPanel } from '../contract/FreeAgentsPage'
import { PlayerDetails } from '../squad/PlayerDetails'
import { MoneyField } from '../../components/MoneyField'
import { MakeOfferDialog, type OfferTarget } from './MakeOfferDialog'
import { LISTED_REASON_LABEL } from '../squad/listedReason'
import type { MarketFilters, PlayerResponse, TransferOffer } from '../../api/types'
import { formatBRL, parseMoneyInput } from '../../lib/money'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import BalanceIcon from '@mui/icons-material/Balance'
import SendIcon from '@mui/icons-material/Send'

const MARKET_PAGE_SIZE = 20

/** The market filter bar's raw form fields — all strings so empty inputs are unambiguous. */
type MarketFilterForm = {
  clubId: string
  position: string
  ageMin: string
  ageMax: string
  overallMin: string
  overallMax: string
  valueMin: string
  valueMax: string
}

const EMPTY_FILTER_FORM: MarketFilterForm = {
  clubId: '', position: '', ageMin: '', ageMax: '', overallMin: '', overallMax: '', valueMin: '', valueMax: '',
}

function parseIntFilter(raw: string): number | undefined {
  if (raw.trim() === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) ? n : undefined
}

function parseMoneyFilter(raw: string): number | undefined {
  const n = parseMoneyInput(raw)
  return Number.isNaN(n) ? undefined : n
}

/** Turns the filter bar's raw form into the query params sent to the API. */
function toMarketFilters(form: MarketFilterForm): MarketFilters {
  return {
    clubId: form.clubId || undefined,
    position: form.position || undefined,
    ageMin: parseIntFilter(form.ageMin),
    ageMax: parseIntFilter(form.ageMax),
    overallMin: parseIntFilter(form.overallMin),
    overallMax: parseIntFilter(form.overallMax),
    valueMin: parseMoneyFilter(form.valueMin),
    valueMax: parseMoneyFilter(form.valueMax),
  }
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

/**
 * A received-proposal card: player identity + the bidding club's offer, then a row of
 * Aceitar / Recusar / Contraproposta actions. Choosing Contraproposta swaps the actions
 * for an inline counter form (amount + Enviar / Cancelar). `player` (from the squad) adds
 * the position/OVR/age when available; the name opens the full details dialog.
 */
function IncomingProposalCard(props: {
  offer: { playerName: string; fromClubName: string; amount: number }
  player: PlayerResponse | undefined
  windowOpen: boolean
  busy: boolean
  isMobile: boolean
  countering: boolean
  counter: string
  onCounterChange: (v: string) => void
  onOpenDetails: () => void
  onAccept: () => void
  onReject: () => void
  onOpenCounter: () => void
  onCancelCounter: () => void
  onSendCounter: () => void
}) {
  const { offer, player } = props
  const actionBtnSx = {
    flex: 1, minWidth: 0, flexDirection: 'column' as const, gap: 0.25,
    py: 1, fontSize: '0.72rem', lineHeight: 1.15, textTransform: 'none',
  }
  const disabled = !props.windowOpen || props.busy

  return (
    <Card component="li" variant="outlined" sx={{ listStyle: 'none' }}>
      <CardContent>
        <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Link
                component="button"
                type="button"
                underline="hover"
                color="inherit"
                onClick={props.onOpenDetails}
                sx={{ font: 'inherit', fontWeight: 700, fontSize: '1.05rem', textAlign: 'left', cursor: 'pointer' }}
              >
                {offer.playerName}
              </Link>
              {player && <Chip size="small" label={player.position} sx={{ bgcolor: '#222', color: '#fff', fontWeight: 700 }} />}
              {player && <Chip size="small" color="primary" label={`OVR ${player.overall}`} sx={{ fontWeight: 800 }} />}
            </Stack>
            <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.25 }}>
              {player ? `${player.age} anos · ` : ''}{offer.fromClubName}
            </Typography>
          </Box>
          <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
            <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>Oferta</Typography>
            <Typography sx={{ fontWeight: 800, color: 'success.main' }}>{formatBRL(offer.amount)}</Typography>
          </Box>
        </Stack>

        <Divider sx={{ my: 1.5 }} />

        {props.countering ? (
          props.isMobile ? (
            <Box>
              <MoneyField
                label="Valor"
                ariaLabel={`Contraproposta para ${offer.playerName}`}
                value={props.counter}
                suggested={offer.amount}
                onChange={props.onCounterChange}
              />
              <Stack direction="row" spacing={1.5} sx={{ mt: 1.5, justifyContent: 'center' }}>
                <Button color="primary" variant="contained" startIcon={<SendIcon />} sx={{ width: 140, height: 40 }}
                  disabled={disabled} onClick={props.onSendCounter}>Enviar</Button>
                <Button color="inherit" variant="outlined" startIcon={<CloseIcon />} sx={{ width: 140, height: 40 }}
                  onClick={props.onCancelCounter}>Cancelar</Button>
              </Stack>
            </Box>
          ) : (
            <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap' }}>
              <MoneyField
                label="Valor"
                ariaLabel={`Contraproposta para ${offer.playerName}`}
                value={props.counter}
                suggested={offer.amount}
                onChange={props.onCounterChange}
              />
              <Stack direction="row" spacing={1}>
                <Button size="small" color="primary" variant="contained" startIcon={<SendIcon />}
                  disabled={disabled} onClick={props.onSendCounter}>Enviar</Button>
                <Button size="small" color="inherit" variant="outlined" startIcon={<CloseIcon />}
                  onClick={props.onCancelCounter}>Cancelar</Button>
              </Stack>
            </Stack>
          )
        ) : props.isMobile ? (
          <Stack direction="row" spacing={1}>
            <Button color="success" variant="contained" sx={actionBtnSx} disabled={disabled} onClick={props.onAccept}>
              <CheckIcon fontSize="small" />
              Aceitar
            </Button>
            <Button color="inherit" variant="outlined" sx={actionBtnSx} disabled={disabled} onClick={props.onReject}>
              <CloseIcon fontSize="small" />
              Recusar
            </Button>
            <Button color="primary" variant="contained" sx={actionBtnSx} disabled={disabled} onClick={props.onOpenCounter}>
              <BalanceIcon fontSize="small" />
              Negociar
            </Button>
          </Stack>
        ) : (
          <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end', flexWrap: 'wrap' }}>
            <Button size="small" color="success" variant="contained" startIcon={<CheckIcon />}
              disabled={disabled} onClick={props.onAccept}>Aceitar</Button>
            <Button size="small" color="inherit" variant="outlined" startIcon={<CloseIcon />}
              disabled={disabled} onClick={props.onReject}>Recusar</Button>
            <Button size="small" color="primary" variant="contained" startIcon={<BalanceIcon />}
              disabled={disabled} onClick={props.onOpenCounter}>Negociar</Button>
          </Stack>
        )}
      </CardContent>
    </Card>
  )
}

export function TransferMarketPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [tab, setTab] = useState(0)
  const [detailsPlayer, setDetailsPlayer] = useState<PlayerResponse | null>(null)
  // The market player a transfer offer is being drafted for (opens MakeOfferDialog).
  const [offerTarget, setOfferTarget] = useState<OfferTarget | null>(null)
  // The incoming offer whose inline counter-proposal form is open (one at a time).
  const [counteringId, setCounteringId] = useState<string | null>(null)

  // Market filter bar: draft form values vs. the applied filters actually sent to the API —
  // "Filtrar" copies the draft over and resets to page 1.
  const [filterForm, setFilterForm] = useState<MarketFilterForm>(EMPTY_FILTER_FORM)
  const [appliedFilters, setAppliedFilters] = useState<MarketFilters>({})
  const [page, setPage] = useState(1)
  const applyFilters = () => {
    setAppliedFilters(toMarketFilters(filterForm))
    setPage(1)
  }

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const clubsQuery = useQuery({ queryKey: ['clubs', saveGameId], queryFn: () => getClubs(saveGameId, token ?? ''), enabled })
  const market = useQuery({
    queryKey: ['market', saveGameId, appliedFilters, page],
    queryFn: () => getMarket(saveGameId, appliedFilters, page, MARKET_PAGE_SIZE, token ?? ''),
    enabled,
  })
  const offers = useQuery({ queryKey: ['offers', saveGameId], queryFn: () => getOffers(saveGameId, token ?? ''), enabled })

  // My squad — used to open full player details for a player another club is bidding on.
  const managedClubId = save.data?.managedClubId ?? ''
  const squad = useQuery({
    queryKey: ['squad', saveGameId, managedClubId],
    queryFn: () => getSquad(saveGameId, managedClubId, token ?? ''),
    enabled: Boolean(saveGameId && managedClubId && token),
  })
  const squadById = new Map((squad.data?.players ?? []).map(p => [p.id, p]))

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
    // Resolving offers may clear a "window closing with pending bids" advance block.
    void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
  }
  // The most recent negotiation outcome (offer / accept-counter / respond), surfaced as the
  // feedback banner. Every action that produces an AI decision feeds this — not just makeOffer —
  // so accepting a counter or answering an incoming bid also tells the user what happened.
  const [lastResult, setLastResult] = useState<TransferOffer | null>(null)
  const showResult = (offer: TransferOffer) => {
    invalidate()
    setLastResult(offer)
  }

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: (offer) => {
      showResult(offer)
      setOfferTarget(null)
    },
  })
  const acceptMut = useMutation({ mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''), onSuccess: showResult })
  const withdrawMut = useMutation({ mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''), onSuccess: invalidate })

  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onSuccess: showResult,
  })

  // Date-based window status from the backend (the single source of truth).
  const windowOpen = save.data?.transferWindowOpen ?? true
  const outgoing = (offers.data ?? []).filter(o => o.direction === 'OUTGOING' && o.status === 'COUNTERED')
  const incoming = (offers.data ?? []).filter(o => o.direction === 'INCOMING' && o.status === 'PENDING')
  const last = lastResult
  const hasProposals = incoming.length > 0 || outgoing.length > 0

  // Land on the market instead of the (disabled) proposals tab when there's nothing to respond to.
  useEffect(() => {
    if (offers.isSuccess && !hasProposals && tab === 0) setTab(1)
  }, [offers.isSuccess, hasProposals, tab])

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Mercado de Transferências</Typography>

      {!windowOpen && tab !== 2 && <Alert severity="error" sx={{ mb: 2 }}>Janela de transferências fechada.</Alert>}
      {last && (
        <Alert severity={last.status === 'REJECTED' ? 'error' : 'success'} sx={{ mb: 2 }}>
          {last.playerName}: {last.status === 'ACCEPTED' ? 'Proposta aceita!'
            : last.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(last.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </Alert>
      )}
      {offerMut.isError && <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(offerMut.error, 'Erro ao enviar proposta.')}</Alert>}

      {isMobile ? (
        <FormControl size="small" fullWidth sx={{ mb: 2 }}>
          <NativeSelect
            value={tab}
            onChange={e => setTab(Number(e.target.value))}
            inputProps={{ 'aria-label': 'Selecionar seção' }}
            startAdornment={
              tab === 0 && incoming.length > 0 ? (
                <Box
                  title="Novas propostas recebidas"
                  sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: 'error.main', flexShrink: 0, mr: 1 }}
                />
              ) : null
            }
          >
            <option value={0} disabled={!hasProposals}>Propostas recebidas</option>
            <option value={1}>Jogadores disponíveis</option>
            <option value={2}>Agentes livres</option>
          </NativeSelect>
        </FormControl>
      ) : (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" sx={{ mb: 2 }}>
          <Tab
            disabled={!hasProposals}
            label={
              <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                <span>Propostas recebidas</span>
                {incoming.length > 0 && (
                  <Box
                    title="Novas propostas recebidas"
                    sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'error.main' }}
                  />
                )}
              </Stack>
            }
          />
          <Tab label="Jogadores disponíveis" />
          <Tab label="Agentes livres" />
        </Tabs>
      )}

      {tab === 0 && (
        <>
          {(acceptMut.isError || withdrawMut.isError || respondMut.isError) && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {errorMessage(acceptMut.error ?? withdrawMut.error ?? respondMut.error, 'Erro ao processar a proposta.')}
            </Alert>
          )}
          {incoming.length === 0 && outgoing.length === 0 && (
            <Typography sx={{ color: 'text.secondary' }}>Nenhuma proposta recebida no momento.</Typography>
          )}

          {incoming.length > 0 && (
            <Stack component="ul" spacing={1.5} sx={{ listStyle: 'none', p: 0, m: 0, mb: 3 }}>
            {incoming.map(o => (
              <IncomingProposalCard
                key={o.id}
                offer={o}
                player={squadById.get(o.playerId)}
                windowOpen={windowOpen}
                busy={respondMut.isPending}
                isMobile={isMobile}
                countering={counteringId === o.id}
                counter={counters[o.id] ?? ''}
                onCounterChange={v => setCounters(c => ({ ...c, [o.id]: v }))}
                onOpenDetails={() => { const p = squadById.get(o.playerId); if (p) setDetailsPlayer(p) }}
                onAccept={() => respondMut.mutate({ offerId: o.id, action: 'ACCEPT', counterAmount: null })}
                onReject={() => respondMut.mutate({ offerId: o.id, action: 'REJECT', counterAmount: null })}
                onOpenCounter={() => setCounteringId(o.id)}
                onCancelCounter={() => setCounteringId(null)}
                onSendCounter={() => {
                  respondMut.mutate({ offerId: o.id, action: 'COUNTER', counterAmount: parsedOr(counters[o.id], o.amount) })
                  setCounteringId(null)
                }}
              />
            ))}
            </Stack>
          )}

          {outgoing.length > 0 && (
            <Box>
              <Typography variant="h6" sx={{ mb: 1 }}>Contrapropostas recebidas</Typography>
          <Stack component="ul" spacing={1.5} sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {outgoing.map(o => (
              <Card key={o.id} component="li" variant="outlined" sx={{ listStyle: 'none' }}>
                <CardContent>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography sx={{ fontWeight: 700 }}>{o.playerName}</Typography>
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        contraproposta de{' '}
                        <Box component="span" sx={{ fontWeight: 700, color: 'text.primary' }}>{formatBRL(o.counterAmount ?? 0)}</Box>
                      </Typography>
                    </Box>
                    <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                      <Button color="secondary" disabled={!windowOpen || acceptMut.isPending}
                        onClick={() => acceptMut.mutate(o.id)}>Aceitar</Button>
                      <Button variant="outlined" color="inherit" disabled={withdrawMut.isPending}
                        onClick={() => withdrawMut.mutate(o.id)}>Desistir</Button>
                    </Stack>
                  </Stack>
                </CardContent>
              </Card>
            ))}
          </Stack>
            </Box>
          )}
        </>
      )}

      {tab === 1 && (
        <>
          {/* Filter bar */}
          <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'flex-end', flexWrap: 'wrap', mb: 2 }}>
            <FormControl size="small" sx={{ minWidth: 160 }}>
              <NativeSelect
                value={filterForm.clubId}
                onChange={e => setFilterForm(f => ({ ...f, clubId: e.target.value }))}
                inputProps={{ 'aria-label': 'Clube' }}
              >
                <option value="">Todos os clubes</option>
                {(clubsQuery.data ?? []).filter(c => c.id !== managedClubId).map(c => (
                  <option key={c.id} value={c.id}>{c.commonName ?? c.name}</option>
                ))}
              </NativeSelect>
            </FormControl>
            <FormControl size="small" sx={{ minWidth: 140 }}>
              <NativeSelect
                value={filterForm.position}
                onChange={e => setFilterForm(f => ({ ...f, position: e.target.value }))}
                inputProps={{ 'aria-label': 'Posição' }}
              >
                <option value="">Todas as posições</option>
                <option value="GK">Goleiro</option>
                <option value="DEF">Defensor</option>
                <option value="MID">Meio-campo</option>
                <option value="ATT">Atacante</option>
              </NativeSelect>
            </FormControl>
            <TextField
              label="Idade mín"
              type="number"
              size="small"
              value={filterForm.ageMin}
              onChange={e => setFilterForm(f => ({ ...f, ageMin: e.target.value }))}
              sx={{ width: 100 }}
              slotProps={{ htmlInput: { 'aria-label': 'Idade mínima', min: 0 } }}
            />
            <TextField
              label="Idade máx"
              type="number"
              size="small"
              value={filterForm.ageMax}
              onChange={e => setFilterForm(f => ({ ...f, ageMax: e.target.value }))}
              sx={{ width: 100 }}
              slotProps={{ htmlInput: { 'aria-label': 'Idade máxima', min: 0 } }}
            />
            <TextField
              label="OVR mín"
              type="number"
              size="small"
              value={filterForm.overallMin}
              onChange={e => setFilterForm(f => ({ ...f, overallMin: e.target.value }))}
              sx={{ width: 100 }}
              slotProps={{ htmlInput: { 'aria-label': 'Overall mínimo', min: 0, max: 99 } }}
            />
            <TextField
              label="OVR máx"
              type="number"
              size="small"
              value={filterForm.overallMax}
              onChange={e => setFilterForm(f => ({ ...f, overallMax: e.target.value }))}
              sx={{ width: 100 }}
              slotProps={{ htmlInput: { 'aria-label': 'Overall máximo', min: 0, max: 99 } }}
            />
            <MoneyField
              label="Valor mín"
              ariaLabel="Valor mínimo"
              value={filterForm.valueMin}
              suggested={0}
              onChange={v => setFilterForm(f => ({ ...f, valueMin: v }))}
            />
            <MoneyField
              label="Valor máx"
              ariaLabel="Valor máximo"
              value={filterForm.valueMax}
              suggested={0}
              onChange={v => setFilterForm(f => ({ ...f, valueMax: v }))}
            />
            <Button variant="contained" onClick={applyFilters} sx={{ height: 40 }}>Filtrar</Button>
          </Stack>

          {market.isLoading ? (
            <Typography>Carregando mercado…</Typography>
          ) : market.error ? (
            <Typography color="error">Erro ao carregar o mercado.</Typography>
          ) : (market.data?.content ?? []).length === 0 ? (
            <Typography sx={{ color: 'text.secondary' }}>Nenhum jogador listado com esses filtros.</Typography>
          ) : (
            <>
              <Stack spacing={1.5}>
                {(market.data?.content ?? []).map(p => (
                <Card key={p.playerId} variant="outlined" sx={{ '&:hover': { borderColor: 'primary.main' } }}>
                  <CardContent>
                    {/* Identity + market value */}
                    <Stack
                      direction={{ xs: 'column', sm: 'row' }}
                      spacing={1}
                      sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                          <Typography variant="h6" sx={{ fontWeight: 700 }}>{p.name}</Typography>
                          <Chip size="small" label={p.position} sx={{ bgcolor: '#222', color: '#fff', fontWeight: 700 }} />
                          <Chip size="small" color="primary" label={`OVR ${p.overall}`} sx={{ fontWeight: 800 }} />
                          {p.listedReason && (
                            <Chip size="small" color="warning" variant="outlined" label={LISTED_REASON_LABEL[p.listedReason]} />
                          )}
                        </Stack>
                        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.25 }}>
                          <Box component="span">{p.clubName}</Box> · {p.age} anos
                        </Typography>
                      </Box>
                      <Stack direction="row" spacing={3} sx={{ flexShrink: 0 }}>
                        <Stat label="Valor" value={formatBRL(p.value)} />
                        <Stat label="Salário pedido" value={formatBRL(p.wageDemand)} />
                      </Stack>
                    </Stack>

                    <Divider sx={{ my: 1.5 }} />

                    <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
                      <Button
                        variant="outlined"
                        disabled={!windowOpen}
                        onClick={() => setOfferTarget({
                          playerId: p.playerId, name: p.name, value: p.value, wageDemand: p.wageDemand,
                        })}
                      >
                        Fazer proposta
                      </Button>
                    </Stack>
                  </CardContent>
                </Card>
                ))}
              </Stack>

              {market.data && market.data.totalPages > 1 && (
                <Stack direction="row" sx={{ justifyContent: 'center', mt: 3 }}>
                  <Pagination
                    count={market.data.totalPages}
                    page={page}
                    onChange={(_, p) => setPage(p)}
                  />
                </Stack>
              )}
            </>
          )}
        </>
      )}

      {tab === 2 && <FreeAgentsPanel />}

      <PlayerDetails player={detailsPlayer} onClose={() => setDetailsPlayer(null)} saveGameId={saveGameId} />

      <MakeOfferDialog
        target={offerTarget}
        windowOpen={windowOpen}
        busy={offerMut.isPending}
        onClose={() => setOfferTarget(null)}
        onSubmit={v => offerMut.mutate(v)}
      />
    </Box>
  )
}
