import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Link, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getClubs, getSaveGame, getSquad, getStaffRoster, makeOffer } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { formatBRL, formatThousandsBR } from '../../lib/money'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'
import { MakeOfferDialog, type OfferTarget } from '../transfer/MakeOfferDialog'
import type {
  ClubSummary, PlayerResponse, StaffMember, StaffRole, StaffRoster, TransferOffer,
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
  const holderFor = (role: StaffRole): StaffMember | null => ({
    TECNICO: roster.tecnico,
    ASSISTENTE: roster.assistente,
    PREPARADOR_FISICO: roster.preparadorFisico,
    OLHEIRO: roster.olheiro,
    FISIOTERAPEUTA: roster.fisioterapeuta,
  })[role]
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
              {holder.wage !== null && (
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
 * clubs each player also gets a "Fazer proposta" action that drafts a transfer offer
 * (hidden for the managed club — you can't bid on your own players).
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

  const club = (clubsQuery.data ?? []).find(c => c.id === clubId)
  const isManagedClub = save.data?.managedClubId === clubId
  const windowOpen = save.data?.transferWindowOpen ?? true

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
      disabled={!windowOpen || offerMut.isPending}
      onClick={() => setOfferTarget({
        playerId: player.id, name: player.name, value: player.value, wageDemand: player.wageDemand,
      })}
    >
      Fazer proposta
    </Button>
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
                    <Stack direction="row" sx={{ mt: 1, justifyContent: 'flex-end' }}>
                      {offerButton(player)}
                    </Stack>
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
                    {!isManagedClub && <TableCell align="center">{offerButton(player)}</TableCell>}
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
    </Box>
  )
}
