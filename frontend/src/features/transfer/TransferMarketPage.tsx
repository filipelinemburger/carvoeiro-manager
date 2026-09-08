import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Divider, FormControl, InputAdornment, Link,
  NativeSelect, Pagination, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material'
import {
  getSaveGame, getMarket, getClubs, getOffers, getSquad, getPlayer,
  makeOffer, acceptCounter, withdrawOffer, respondToOffer, loanIn, acceptLoanCounter,
} from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { FreeAgentsPanel } from '../contract/FreeAgentsPage'
import PreContractsPanel from './PreContractsPanel'
import WatchlistPanel from './WatchlistPanel'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'
import { MoneyField } from '../../components/MoneyField'
import { MakeOfferDialog, type OfferTarget } from './MakeOfferDialog'
import { LoanInDialog } from './LoanInDialog'
import { MIN_CONTRACT_YEARS_TO_LOAN } from '../loan/loanMessages'
import { LISTED_REASON_LABEL } from '../squad/listedReason'
import { CARBON } from '../../muiTheme'
import type { LoanOfferResult, MarketFilters, MarketPlayer, PlayerResponse, TransferOffer } from '../../api/types'
import { formatBRL, parseMoneyInput } from '../../lib/money'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import BalanceIcon from '@mui/icons-material/Balance'
import SendIcon from '@mui/icons-material/Send'
import GroupsIcon from '@mui/icons-material/Groups'
import SportsSoccerIcon from '@mui/icons-material/SportsSoccer'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import StarIcon from '@mui/icons-material/Star'
import SortIcon from '@mui/icons-material/Sort'

const MARKET_PAGE_SIZE = 20

/** Client-side sort applied to the current page of results (the API paginates unsorted). */
type SortKey = 'overall_desc' | 'value_desc' | 'value_asc' | 'age_asc' | 'name_asc'

const SORT_LABEL: Record<SortKey, string> = {
  overall_desc: 'Maior OVR', value_desc: 'Maior valor', value_asc: 'Menor valor',
  age_asc: 'Mais jovem', name_asc: 'Nome A-Z',
}

function sortMarketPlayers(players: MarketPlayer[], sort: SortKey): MarketPlayer[] {
  const sorted = [...players]
  switch (sort) {
    case 'overall_desc': return sorted.sort((a, b) => b.overall - a.overall)
    case 'value_desc': return sorted.sort((a, b) => b.value - a.value)
    case 'value_asc': return sorted.sort((a, b) => a.value - b.value)
    case 'age_asc': return sorted.sort((a, b) => a.age - b.age)
    case 'name_asc': return sorted.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'))
  }
}

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

/** Why this market player cannot be borrowed right now, or null when he can be. Mirrors
 * SquadPage's `loanBlockedReason`: the contract-length guard mirrors
 * LoanService.requireLoanableContract, and the already-on-loan guard mirrors the `!isOnLoan()`
 * filter the backend applies before it will even look the player up — a player already out on
 * loan isn't the current club's to re-lend. Explains only — the backend still enforces both. */
function loanInBlockedReason(player: MarketPlayer): string | null {
  if (player.onLoan) {
    return 'Jogador já está emprestado a outro clube.'
  }
  if (player.contractYears < MIN_CONTRACT_YEARS_TO_LOAN) {
    return `Contrato com menos de ${MIN_CONTRACT_YEARS_TO_LOAN} anos não pode ser emprestado.`
  }
  return null
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
              {player && <Chip size="small" label={player.position} sx={{ bgcolor: CARBON.bg600, color: '#E5E7EB', fontWeight: 700 }} />}
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
  // Incoming proposals already carry the full player object (from our own squad) — no fetch
  // needed. Market/free-agent rows only have a bare id, so opening details for one of those
  // fetches it lazily via detailsPlayerId instead.
  const [detailsPlayer, setDetailsPlayer] = useState<PlayerResponse | null>(null)
  const [detailsPlayerId, setDetailsPlayerId] = useState<string | null>(null)
  // Whether the dialog was opened from a rival list (market/free-agents/pre-contracts — the
  // only players eligible for the watch star) vs. an incoming proposal for our own squad.
  const [detailsIsRival, setDetailsIsRival] = useState(false)
  const detailsPlayerQuery = useQuery({
    queryKey: ['player', saveGameId, detailsPlayerId],
    queryFn: () => getPlayer(saveGameId, detailsPlayerId!, token ?? ''),
    enabled: Boolean(saveGameId && detailsPlayerId && token),
  })
  const resolvedDetailsPlayer = detailsPlayer ?? detailsPlayerQuery.data ?? null
  const closeDetails = () => { setDetailsPlayer(null); setDetailsPlayerId(null) }
  const openOwnPlayerDetails = (p: PlayerResponse) => {
    setDetailsPlayer(p)
    setDetailsPlayerId(null)
    setDetailsIsRival(false)
  }
  const openRivalPlayerDetails = (playerId: string) => {
    setDetailsPlayerId(playerId)
    setDetailsPlayer(null)
    setDetailsIsRival(true)
  }
  // The market player a transfer offer is being drafted for (opens MakeOfferDialog).
  const [offerTarget, setOfferTarget] = useState<OfferTarget | null>(null)
  // The incoming offer whose inline counter-proposal form is open (one at a time).
  const [counteringId, setCounteringId] = useState<string | null>(null)
  // The market player a loan-in proposal is being drafted for (opens LoanInDialog) — a bid's
  // alternative on the same row, so this lives beside offerTarget rather than on its own page.
  const [loaningPlayer, setLoaningPlayer] = useState<MarketPlayer | null>(null)
  /** The outcome of the open loan negotiation (accepted / countered / rejected); null before a
   * proposal is sent, and reset whenever the dialog opens for a (possibly different) player. */
  const [loanResult, setLoanResult] = useState<LoanOfferResult | null>(null)

  // Market filter bar: draft form values vs. the applied filters actually sent to the API —
  // "Filtrar" copies the draft over and resets to page 1.
  const [filterForm, setFilterForm] = useState<MarketFilterForm>(EMPTY_FILTER_FORM)
  const [appliedFilters, setAppliedFilters] = useState<MarketFilters>({})
  const [page, setPage] = useState(1)
  const applyFilters = () => {
    setAppliedFilters(toMarketFilters(filterForm))
    setPage(1)
  }
  // Client-side sort of the current (already-filtered, already-paginated) page — the API
  // doesn't take a sort param, so this only reorders what's already on screen.
  const [sortBy, setSortBy] = useState<SortKey>('overall_desc')

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled, staleTime: 30_000 })
  const clubsQuery = useQuery({ queryKey: ['clubs', saveGameId], queryFn: () => getClubs(saveGameId, token ?? ''), enabled, staleTime: Infinity })
  const market = useQuery({
    queryKey: ['market', saveGameId, appliedFilters, page],
    queryFn: () => getMarket(saveGameId, appliedFilters, page, MARKET_PAGE_SIZE, token ?? ''),
    enabled,
    staleTime: Infinity,
  })
  const offers = useQuery({
    queryKey: ['offers', saveGameId],
    queryFn: () => getOffers(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })

  // My squad — used to open full player details for a player another club is bidding on.
  const managedClubId = save.data?.managedClubId ?? ''
  const squad = useQuery({
    queryKey: ['squad', saveGameId, managedClubId],
    queryFn: () => getSquad(saveGameId, managedClubId, token ?? ''),
    enabled: Boolean(saveGameId && managedClubId && token),
  })
  const squadById = new Map((squad.data?.players ?? []).map(p => [p.id, p]))

  // The most recent negotiation outcome (offer / accept-counter / respond), surfaced as the
  // feedback banner. Every action that produces an AI decision feeds this — not just makeOffer —
  // so accepting a counter or answering an incoming bid also tells the user what happened.
  const [lastResult, setLastResult] = useState<TransferOffer | null>(null)

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: (offer) => {
      // Write-through: the AI's decision is unknown until the response arrives, but once it
      // does, upsert it into the cache instead of paying for an offers refetch.
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) => {
        const rest = (old ?? []).filter(o => o.id !== offer.id)
        return [...rest, offer]
      })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (offer.status === 'ACCEPTED') {
        void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
        void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, managedClubId] })
      }
      setLastResult(offer)
      setOfferTarget(null)
    },
  })

  const acceptMut = useMutation({
    mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''),
    onSuccess: (offer) => {
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
        (old ?? []).map(o => (o.id === offer.id ? offer : o)))
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (offer.status === 'ACCEPTED') {
        void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
        void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, managedClubId] })
      }
      setLastResult(offer)
    },
  })

  const withdrawMut = useMutation({
    mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''),
    onMutate: async (id: string) => {
      await queryClient.cancelQueries({ queryKey: ['offers', saveGameId] })
      const previous = queryClient.getQueryData<TransferOffer[]>(['offers', saveGameId])
      queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
        (old ?? []).filter(o => o.id !== id))
      return { previous }
    },
    onError: (_err, _id, context) => {
      if (context?.previous) queryClient.setQueryData(['offers', saveGameId], context.previous)
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })

  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onMutate: async (v) => {
      await queryClient.cancelQueries({ queryKey: ['offers', saveGameId] })
      const previous = queryClient.getQueryData<TransferOffer[]>(['offers', saveGameId])
      // ACCEPT/REJECT are the manager's own explicit, fully-predictable choice — remove the
      // card immediately. COUNTER's outcome depends on the AI's next move, so leave it alone
      // and let onSuccess's write-through handle it once the response arrives.
      if (v.action !== 'COUNTER') {
        queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
          (old ?? []).filter(o => o.id !== v.offerId))
      }
      return { previous }
    },
    onSuccess: (offer, v) => {
      setLastResult(offer)
      if (v.action === 'COUNTER') {
        queryClient.setQueryData<TransferOffer[]>(['offers', saveGameId], (old) =>
          (old ?? []).map(o => (o.id === offer.id ? offer : o)))
      }
    },
    onError: (_err, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['offers', saveGameId], context.previous)
    },
    onSettled: (_offer, _err, v) => {
      if (v.action !== 'COUNTER') {
        // Belt-and-suspenders against any server-side divergence (e.g. a race with a round-advance).
        void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
      }
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (v.action === 'ACCEPT') {
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
        void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, managedClubId] })
      }
    },
  })

  // Invalidations shared by both loan-in mutations below: a borrowed player joins the squad
  // (finances change with the new wage share, the market listing for him can disappear).
  function invalidateAfterLoanIn() {
    void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
    void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, managedClubId] })
  }

  const loanInMut = useMutation({
    mutationFn: (v: { playerId: string; ownerWageShare: number; mayFaceOwner: boolean }) =>
      loanIn(saveGameId, v, token ?? ''),
    onSuccess: (data) => {
      setLoanResult(data)
      // A REJECTED result means nothing moved server-side — nothing went stale.
      if (data.status !== 'REJECTED') invalidateAfterLoanIn()
    },
  })

  const acceptLoanCounterMut = useMutation({
    mutationFn: (offerId: string) => acceptLoanCounter(saveGameId, offerId, token ?? ''),
    onSuccess: (data) => {
      setLoanResult(data)
      if (data.status !== 'REJECTED') invalidateAfterLoanIn()
    },
  })

  // Date-based window status from the backend (the single source of truth).
  const windowOpen = save.data?.transferWindowOpen ?? true
  const currentDate = save.data?.currentDate ? new Date(save.data.currentDate) : null
  const preContractWindowOpen = currentDate
    ? currentDate >= new Date(currentDate.getFullYear(), 6, 15) // July 15 (month is 0-based)
    : false
  const outgoing = (offers.data ?? []).filter(o => o.direction === 'OUTGOING' && o.status === 'COUNTERED')
  const incoming = (offers.data ?? []).filter(o => o.direction === 'INCOMING' && o.status === 'PENDING')
  const last = lastResult
  const hasProposals = incoming.length > 0 || outgoing.length > 0

  // Land on the market instead of the (disabled) proposals tab when there's nothing to respond to.
  // Gated on withdrawMut/respondMut being idle: those two mutations are the only ones that
  // optimistically remove an offer via onMutate, so hasProposals can go transiently false while
  // one is in flight. Waiting for isPending to clear means we react to the settled data (already
  // corrected by onError's rollback, which runs before onSettled) instead of the transient dip.
  useEffect(() => {
    if (offers.isSuccess && !hasProposals && tab === 0 && !withdrawMut.isPending && !respondMut.isPending) {
      setTab(1)
    }
  }, [offers.isSuccess, hasProposals, tab, withdrawMut.isPending, respondMut.isPending])

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Mercado de Transferências</Typography>

      {!windowOpen && tab !== 2 && tab !== 4 && <Alert severity="error" sx={{ mb: 2 }}>Janela de transferências fechada.</Alert>}
      {last && (
        <Alert severity={last.status === 'REJECTED' ? 'error' : 'success'} sx={{ mb: 2 }}>
          {last.playerName}: {last.status === 'ACCEPTED' ? 'Proposta aceita!'
            : last.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(last.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </Alert>
      )}
      {offerMut.isError && <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(offerMut.error, 'Erro ao enviar proposta.')}</Alert>}
      {(loanInMut.isError || acceptLoanCounterMut.isError) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {errorMessage(loanInMut.error ?? acceptLoanCounterMut.error, 'Erro ao negociar o empréstimo.')}
        </Alert>
      )}

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
            <option value={3}>Pré-contratos</option>
            <option value={4}>Observados</option>
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
          <Tab label="Pré-contratos" />
          <Tab label="Observados" />
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
                onOpenDetails={() => { const p = squadById.get(o.playerId); if (p) openOwnPlayerDetails(p) }}
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
          <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'flex-end', flexWrap: 'wrap', mb: 1.5 }}>
            <FormControl size="small" sx={{ minWidth: 170 }}>
              <NativeSelect
                value={filterForm.clubId}
                onChange={e => setFilterForm(f => ({ ...f, clubId: e.target.value }))}
                inputProps={{ 'aria-label': 'Clube' }}
                startAdornment={<GroupsIcon fontSize="small" sx={{ color: 'text.secondary', mr: 0.75 }} />}
              >
                <option value="">Todos os clubes</option>
                {(clubsQuery.data ?? []).filter(c => c.id !== managedClubId).map(c => (
                  <option key={c.id} value={c.id}>{c.commonName ?? c.name}</option>
                ))}
              </NativeSelect>
            </FormControl>
            <FormControl size="small" sx={{ minWidth: 150 }}>
              <NativeSelect
                value={filterForm.position}
                onChange={e => setFilterForm(f => ({ ...f, position: e.target.value }))}
                inputProps={{ 'aria-label': 'Posição' }}
                startAdornment={<SportsSoccerIcon fontSize="small" sx={{ color: 'text.secondary', mr: 0.75 }} />}
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
              sx={{ width: 110 }}
              slotProps={{
                htmlInput: { 'aria-label': 'Idade mínima', min: 0 },
                input: { startAdornment: <InputAdornment position="start"><CalendarMonthIcon fontSize="small" sx={{ color: 'text.secondary' }} /></InputAdornment> },
              }}
            />
            <TextField
              label="Idade máx"
              type="number"
              size="small"
              value={filterForm.ageMax}
              onChange={e => setFilterForm(f => ({ ...f, ageMax: e.target.value }))}
              sx={{ width: 110 }}
              slotProps={{
                htmlInput: { 'aria-label': 'Idade máxima', min: 0 },
                input: { startAdornment: <InputAdornment position="start"><CalendarMonthIcon fontSize="small" sx={{ color: 'text.secondary' }} /></InputAdornment> },
              }}
            />
            <TextField
              label="OVR mín"
              type="number"
              size="small"
              value={filterForm.overallMin}
              onChange={e => setFilterForm(f => ({ ...f, overallMin: e.target.value }))}
              sx={{ width: 110 }}
              slotProps={{
                htmlInput: { 'aria-label': 'Overall mínimo', min: 0, max: 99 },
                input: { startAdornment: <InputAdornment position="start"><StarIcon fontSize="small" sx={{ color: 'text.secondary' }} /></InputAdornment> },
              }}
            />
            <TextField
              label="OVR máx"
              type="number"
              size="small"
              value={filterForm.overallMax}
              onChange={e => setFilterForm(f => ({ ...f, overallMax: e.target.value }))}
              sx={{ width: 110 }}
              slotProps={{
                htmlInput: { 'aria-label': 'Overall máximo', min: 0, max: 99 },
                input: { startAdornment: <InputAdornment position="start"><StarIcon fontSize="small" sx={{ color: 'text.secondary' }} /></InputAdornment> },
              }}
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

          {/* Sort control — reorders only the players already on this page (the API isn't sorted). */}
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 2 }}>
            <SortIcon fontSize="small" sx={{ color: 'text.secondary' }} />
            <FormControl size="small" sx={{ minWidth: 160 }}>
              <NativeSelect
                value={sortBy}
                onChange={e => setSortBy(e.target.value as SortKey)}
                inputProps={{ 'aria-label': 'Ordenar por' }}
              >
                {(Object.keys(SORT_LABEL) as SortKey[]).map(key => (
                  <option key={key} value={key}>{SORT_LABEL[key]}</option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>

          {market.isLoading ? (
            <Typography>Carregando mercado…</Typography>
          ) : market.error ? (
            <Typography color="error">Erro ao carregar o mercado.</Typography>
          ) : (market.data?.content ?? []).length === 0 ? (
            <Typography sx={{ color: 'text.secondary' }}>Nenhum jogador listado com esses filtros.</Typography>
          ) : (
            <>
              <Stack spacing={1.25}>
                {sortMarketPlayers(market.data?.content ?? [], sortBy).map(p => (
                <Card key={p.playerId} variant="outlined" sx={{ '&:hover': { borderColor: 'primary.main' } }}>
                  <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                    <Stack
                      direction={{ xs: 'column', md: 'row' }}
                      spacing={1.5}
                      sx={{ alignItems: { md: 'center' } }}
                    >
                      <Box sx={{ minWidth: 0, flex: 1 }}>
                        <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                          <Link
                            component="button"
                            type="button"
                            underline="hover"
                            color="inherit"
                            onClick={() => openRivalPlayerDetails(p.playerId)}
                            sx={{ font: 'inherit', fontWeight: 700, textAlign: 'left', cursor: 'pointer' }}
                          >
                            {p.name}
                          </Link>
                          <Chip size="small" label={p.position} sx={{ bgcolor: CARBON.bg600, color: '#E5E7EB', fontWeight: 700, height: 20 }} />
                          <Chip size="small" color="primary" label={`OVR ${p.overall}`} sx={{ fontWeight: 800, height: 20 }} />
                          {p.listedReason && (
                            <Chip size="small" color="warning" variant="outlined" label={LISTED_REASON_LABEL[p.listedReason]} />
                          )}
                        </Stack>
                        <Typography variant="body2" sx={{ color: 'text.secondary' }} noWrap>
                          <Box component="span">{p.clubName}</Box> · {p.age} anos
                        </Typography>
                      </Box>
                      <Stack direction="row" spacing={3} sx={{ flexShrink: 0 }}>
                        <Stat label="Valor" value={formatBRL(p.value)} />
                        <Stat label="Salário pedido" value={formatBRL(p.wageDemand)} />
                      </Stack>
                      <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                        <Button
                          variant="outlined"
                          size="small"
                          disabled={!windowOpen || p.onLoan}
                          onClick={() => setOfferTarget({
                            playerId: p.playerId, name: p.name, value: p.value, wageDemand: p.wageDemand,
                          })}
                        >
                          Fazer proposta
                        </Button>
                        <Stack spacing={0.25} sx={{ alignItems: 'flex-end' }}>
                          <Button
                            variant="outlined"
                            color="secondary"
                            size="small"
                            disabled={!windowOpen || loanInBlockedReason(p) !== null}
                            onClick={() => { setLoaningPlayer(p); setLoanResult(null) }}
                          >
                            Pedir emprestado
                          </Button>
                          {loanInBlockedReason(p) && (
                            <Typography variant="caption" color="error" sx={{ textAlign: 'right' }}>
                              {loanInBlockedReason(p)}
                            </Typography>
                          )}
                        </Stack>
                      </Stack>
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

      {tab === 2 && <FreeAgentsPanel onOpenDetails={openRivalPlayerDetails} />}

      {tab === 3 && (
        <PreContractsPanel
          saveGameId={saveGameId}
          token={token ?? ''}
          managedClubId={managedClubId}
          windowOpen={preContractWindowOpen}
        />
      )}

      {tab === 4 && <WatchlistPanel saveGameId={saveGameId} token={token ?? ''} />}

      <PlayerDetails
        player={resolvedDetailsPlayer}
        onClose={closeDetails}
        saveGameId={saveGameId}
        isOwnPlayer={!detailsIsRival}
        watchAction={detailsIsRival && resolvedDetailsPlayer ? (
          <WatchStar saveGameId={saveGameId} playerId={resolvedDetailsPlayer.id} />
        ) : undefined}
      />

      <MakeOfferDialog
        target={offerTarget}
        windowOpen={windowOpen}
        busy={offerMut.isPending}
        onClose={() => setOfferTarget(null)}
        onSubmit={v => offerMut.mutate(v)}
      />

      <LoanInDialog
        player={loaningPlayer}
        pending={loanInMut.isPending || acceptLoanCounterMut.isPending}
        result={loanResult}
        onSubmit={v => { if (loaningPlayer) loanInMut.mutate({ playerId: loaningPlayer.playerId, ...v }) }}
        onAcceptCounter={() => { if (loanResult) acceptLoanCounterMut.mutate(loanResult.offerId) }}
        onClose={() => { setLoaningPlayer(null); setLoanResult(null) }}
      />
    </Box>
  )
}
