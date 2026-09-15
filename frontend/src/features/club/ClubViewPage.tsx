import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Link, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import {
  acceptLoanCounter, getClubs, getLoanOffers, getSaveGame, getSquad, getStaffRoster, loanIn, makeOffer,
  withdrawLoanOffer,
} from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { formatBRL, formatThousandsBR } from '../../lib/money'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'
import { MakeOfferDialog, type OfferTarget } from '../transfer/MakeOfferDialog'
import { LoanInDialog } from '../transfer/LoanInDialog'
import { PendingLoanOffers } from '../loan/PendingLoanOffers'
import { invalidateAfterLoanMove } from '../loan/loanInvalidation'
import { loanInBlockedReason } from '../loan/loanMessages'
import type {
  ClubSummary, LoanOfferResult, PlayerResponse, StaffMember, StaffRole, StaffRoster, TransferOffer,
} from '../../api/types'
import { positionShort } from '../players/positionLabels'
import { STAFF_ATTRIBUTE_LABELS, STAFF_QUALITY_LABEL, STAFF_ROLE_LABEL, STAFF_ROLE_ORDER } from '../staff/staffLabels'

// Display order for the roster: goalkeepers, then defenders, midfielders, attackers.
const POSITION_ORDER: Record<PlayerResponse['position'], number> = { GK: 0, DEF: 1, MID: 2, ATT: 3 }

// Coarse fallbacks when a player has no detailed position (legacy payloads).
const COARSE_POS_SHORT: Record<PlayerResponse['position'], string> = { GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }

/** Short position abbr: detailed when present, else coarse. */
function positionLabel(player: PlayerResponse): string {
  return positionShort(player, COARSE_POS_SHORT)
}

/** "Estádio X · Cidade/UF · N lugares", each segment omitted when its data is null so a
 * club missing city/state (or an unseeded club without stadium data) still renders cleanly
 * without a dangling separator. Null when nothing is available at all. */
function clubLocationLine(club: ClubSummary): string | null {
  const segments: string[] = []
  if (club.stadiumName) segments.push(club.stadiumName)
  const place = [club.city, club.state].filter(Boolean).join('/')
  if (place) segments.push(place)
  if (club.stadiumCapacity != null) segments.push(`${formatThousandsBR(club.stadiumCapacity)} lugares`)
  return segments.length > 0 ? segments.join(' · ') : null
}

/**
 * A club's coaching staff, as seen from its profile page. Roles with no holder are skipped rather
 * than shown as "Vaga em aberto". On your own staff page an empty slot is a call to action, but
 * here it would assert something false: per the design spec, an unfilled slot on an AI club is a
 * fact about what the research could find, not about the club — Avaí certainly employs a scout,
 * we just could not source one.
 *
 * The head-coach slot is asymmetric: on a rival club it is a normal employee with a quality,
 * a wage and real attributes, but on the managed club it stands for the player, who is not a
 * hire — that entry arrives with a null quality/wage and placeholder attributes, so it renders
 * as a bare name. The guard is on the null fields, never on which club is being viewed.
 */
function Commission({ roster }: { roster: StaffRoster | undefined }) {
  if (!roster) return null
  // `?? null` is not redundant: the object literal is typed as total, so TypeScript cannot see
  // that a payload missing a key yields undefined — which would survive the `!== null` filter
  // below and throw on holder.name, blanking the whole club page instead of one row.
  const holderFor = (role: StaffRole): StaffMember | null => ({
    TECNICO: roster.tecnico,
    ASSISTENTE: roster.assistente,
    PREPARADOR_FISICO: roster.preparadorFisico,
    OLHEIRO: roster.olheiro,
    FISIOTERAPEUTA: roster.fisioterapeuta,
  })[role] ?? null
  const filled = STAFF_ROLE_ORDER.filter(role => holderFor(role) !== null)
  if (filled.length === 0) return null

  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>Comissão Técnica</Typography>
      <Stack spacing={0.5}>
        {filled.map(role => {
          const holder = holderFor(role)!
          const [primaryLabel, secondaryLabel] = STAFF_ATTRIBUTE_LABELS[role]
          return (
            <Stack
              key={role}
              direction="row"
              spacing={1}
              sx={{ alignItems: 'baseline', flexWrap: 'wrap', rowGap: 0.5 }}
            >
              <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>
                {STAFF_ROLE_LABEL[role]}
              </Typography>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{holder.name}</Typography>
              {holder.quality && (
                <Chip size="small" label={STAFF_QUALITY_LABEL[holder.quality]} />
              )}
              {/* != null, not !== null: an absent field would otherwise reach formatBRL and
                  render "R$ NaN/mês". */}
              {holder.wage != null && (
                <Typography variant="body2" color="text.secondary">{formatBRL(holder.wage)}/mês</Typography>
              )}
              {holder.quality && (
                <>
                  <Chip size="small" label={`${primaryLabel}: ${holder.primaryAttribute}`} />
                  <Chip size="small" label={`${secondaryLabel}: ${holder.secondaryAttribute}`} />
                </>
              )}
            </Stack>
          )
        })}
      </Stack>
    </Box>
  )
}

/**
 * Read-only view of any club's roster. Players open the full details modal; for other
 * clubs each player also gets a "Fazer proposta" action that drafts a transfer offer and a
 * "Pedir emprestado" action that proposes a loan (both hidden for the managed club — you can't
 * bid on or borrow your own players). This is the borrow door for players who aren't
 * transfer-listed: the market only ever shows listed ones, and the backend never required it.
 */
export function ClubViewPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const [detailsPlayer, setDetailsPlayer] = useState<PlayerResponse | null>(null)
  const [offerTarget, setOfferTarget] = useState<OfferTarget | null>(null)
  const [lastResult, setLastResult] = useState<TransferOffer | null>(null)
  // The rival player a loan-in proposal is being drafted for (opens LoanInDialog), and the outcome
  // of that negotiation — null before a proposal is sent, reset whenever the dialog opens.
  const [loaningPlayer, setLoaningPlayer] = useState<{ playerId: string; name: string } | null>(null)
  const [loanResult, setLoanResult] = useState<LoanOfferResult | null>(null)

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled,
    staleTime: 30_000,
  })
  const clubsQuery = useQuery({
    queryKey: ['clubs', saveGameId],
    queryFn: () => getClubs(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })
  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  // Same queryKey shape as StaffPage so both share one cache entry.
  const staffRoster = useQuery({
    queryKey: ['staffRoster', saveGameId, clubId],
    queryFn: () => getStaffRoster(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  // Same queryKey as TransferMarketPage/SquadPage so a counter made anywhere shows up here.
  const loanOffersQuery = useQuery({
    queryKey: ['loanOffers', saveGameId],
    queryFn: () => getLoanOffers(saveGameId, token ?? ''),
    enabled,
    staleTime: 30_000,
  })

  const club = (clubsQuery.data ?? []).find(c => c.id === clubId)
  const managedClubId = save.data?.managedClubId ?? ''
  const isManagedClub = managedClubId === clubId
  const windowOpen = save.data?.transferWindowOpen ?? true

  // A completed loan moves a player out of the viewed club too, so its roster here goes stale
  // alongside everything invalidateAfterLoanMove covers for the managed club.
  const invalidateAfterLoan = (result: LoanOfferResult) => {
    setLoanResult(result)
    if (result.status === 'REJECTED') return
    invalidateAfterLoanMove(queryClient, saveGameId, managedClubId)
    void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
  }

  const loanInMut = useMutation({
    mutationFn: (v: { playerId: string; ownerWageShare: number; mayFaceOwner: boolean }) =>
      loanIn(saveGameId, v, token ?? ''),
    onSuccess: invalidateAfterLoan,
  })

  const acceptLoanCounterMut = useMutation({
    mutationFn: (offerId: string) => acceptLoanCounter(saveGameId, offerId, token ?? ''),
    onSuccess: invalidateAfterLoan,
  })

  const withdrawLoanOfferMut = useMutation({
    mutationFn: (offerId: string) => withdrawLoanOffer(saveGameId, offerId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['loanOffers', saveGameId] })
    },
  })

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: (offer) => {
      setLastResult(offer)
      setOfferTarget(null)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      if (offer.status === 'ACCEPTED') {
        void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, save.data?.managedClubId ?? ''] })
        void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, save.data?.managedClubId ?? ''] })
      }
    },
  })

  const players = [...(data?.players ?? [])].sort(
    (a, b) => POSITION_ORDER[a.position] - POSITION_ORDER[b.position] || b.overall - a.overall,
  )

  // The player's name doubles as the trigger for the details dialog.
  const playerNameButton = (player: PlayerResponse) => (
    <Link
      component="button"
      type="button"
      underline="hover"
      color="inherit"
      onClick={() => setDetailsPlayer(player)}
      sx={{ font: 'inherit', fontWeight: 600, textAlign: 'left', verticalAlign: 'baseline', cursor: 'pointer' }}
    >
      {player.name}
    </Link>
  )

  const offerButton = (player: PlayerResponse) => (
    <Button
      size="small"
      variant="outlined"
      // A player shown here on loan (onLoanFromClubName set) is only borrowed by this club —
      // it doesn't own him, so a transfer offer can't go through. TransferMarketPage already
      // disables "Fazer proposta" the same way (its p.onLoan); without this, the button here
      // stayed live and the backend refused with PLAYER_ALREADY_ON_LOAN.
      disabled={!windowOpen || offerMut.isPending || Boolean(player.onLoanFromClubName)}
      onClick={() => setOfferTarget({
        playerId: player.id, name: player.name, value: player.value, wageDemand: player.wageDemand,
      })}
    >
      Fazer proposta
    </Button>
  )

  const loanButton = (player: PlayerResponse) => {
    const blockedReason = loanInBlockedReason({
      contractYears: player.contractYears,
      onLoan: Boolean(player.onLoanFromClubName),
    })
    return (
      <Stack spacing={0.25} sx={{ alignItems: 'flex-end' }}>
        <Button
          size="small"
          variant="outlined"
          color="secondary"
          disabled={!windowOpen || blockedReason !== null}
          onClick={() => { setLoaningPlayer({ playerId: player.id, name: player.name }); setLoanResult(null) }}
        >
          Pedir emprestado
        </Button>
        {blockedReason && (
          <Typography variant="caption" color="error" sx={{ textAlign: 'right' }}>
            {blockedReason}
          </Typography>
        )}
      </Stack>
    )
  }

  const playerActions = (player: PlayerResponse) => (
    <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end', alignItems: 'flex-start' }}>
      {offerButton(player)}
      {loanButton(player)}
    </Stack>
  )

  const clubName = club ? (club.commonName ?? club.name) : ''
  const locationLine = club ? clubLocationLine(club) : null

  return (
    <Box>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
        {clubName ? `Elenco — ${clubName}` : 'Elenco'}
      </Typography>
      {club && (
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
          Reputação {club.reputation} · {club.squadSize} jogadores
        </Typography>
      )}
      {locationLine && (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {locationLine}
        </Typography>
      )}

      {!windowOpen && !isManagedClub && (
        <Alert severity="warning" sx={{ mt: 2 }}>Janela de transferências fechada.</Alert>
      )}
      {lastResult && (
        <Alert severity={lastResult.status === 'REJECTED' ? 'error' : 'success'} sx={{ mt: 2 }}>
          {lastResult.playerName}: {lastResult.status === 'ACCEPTED' ? 'Proposta aceita!'
            : lastResult.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(lastResult.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </Alert>
      )}
      {offerMut.isError && <Alert severity="error" sx={{ mt: 2 }}>{errorMessage(offerMut.error, 'Erro ao enviar proposta.')}</Alert>}
      {(loanInMut.isError || acceptLoanCounterMut.isError) && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {errorMessage(loanInMut.error ?? acceptLoanCounterMut.error, 'Erro ao pedir empréstimo.')}
        </Alert>
      )}

      {!isManagedClub && (
        <Box sx={{ mt: 2 }}>
          <PendingLoanOffers
            offers={loanOffersQuery.data ?? []}
            windowOpen={windowOpen}
            busy={acceptLoanCounterMut.isPending || withdrawLoanOfferMut.isPending}
            onAccept={id => acceptLoanCounterMut.mutate(id)}
            onWithdraw={id => withdrawLoanOfferMut.mutate(id)}
          />
        </Box>
      )}

      {isLoading && <Typography sx={{ mt: 2 }}>Carregando elenco…</Typography>}
      {error && <Typography sx={{ mt: 2 }} color="error">Erro ao carregar elenco.</Typography>}

      {!isLoading && !error && (
        isMobile ? (
          <Stack spacing={1.5} sx={{ mt: 2 }}>
            {players.map(player => (
              <Card key={player.id} variant="outlined">
                <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      {playerNameButton(player)}
                      {player.transferListed && (
                        <Chip size="small" color="warning" label="Listado" sx={{ ml: 0.75, height: 20 }} />
                      )}
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        {player.age} anos · Valor {formatBRL(player.value)}
                      </Typography>
                    </Box>
                    <Chip size="small" label={positionLabel(player)} />
                    <Chip size="small" color="primary" label={`OVR ${player.overall}`} />
                  </Stack>
                  {!isManagedClub && (
                    <Box sx={{ mt: 1 }}>{playerActions(player)}</Box>
                  )}
                </CardContent>
              </Card>
            ))}
          </Stack>
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Nome</TableCell>
                  <TableCell align="center">Pos</TableCell>
                  <TableCell align="center">Idade</TableCell>
                  <TableCell align="center">OVR</TableCell>
                  <TableCell align="right">Valor</TableCell>
                  <TableCell align="right">Salário pedido</TableCell>
                  {!isManagedClub && <TableCell align="center" />}
                </TableRow>
              </TableHead>
              <TableBody>
                {players.map(player => (
                  <TableRow key={player.id} hover>
                    <TableCell>
                      {playerNameButton(player)}
                      {player.transferListed && (
                        <Chip size="small" color="warning" label="Listado" sx={{ ml: 0.75, height: 20 }} />
                      )}
                    </TableCell>
                    <TableCell align="center">{positionLabel(player)}</TableCell>
                    <TableCell align="center">{player.age}</TableCell>
                    <TableCell align="center">{player.overall}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatBRL(player.value)}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatBRL(player.wageDemand)}</TableCell>
                    {!isManagedClub && <TableCell align="right">{playerActions(player)}</TableCell>}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )
      )}

      <Commission roster={staffRoster.data} />

      <PlayerDetails
        player={detailsPlayer}
        onClose={() => setDetailsPlayer(null)}
        saveGameId={saveGameId}
        isOwnPlayer={isManagedClub}
        watchAction={!isManagedClub && detailsPlayer ? (
          <WatchStar saveGameId={saveGameId} playerId={detailsPlayer.id} />
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
