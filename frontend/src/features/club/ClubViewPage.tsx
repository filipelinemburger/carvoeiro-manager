import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Link, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getClubs, getSaveGame, getSquad, makeOffer } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { formatBRL } from '../../lib/money'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'
import { MakeOfferDialog, type OfferTarget } from '../transfer/MakeOfferDialog'
import type { PlayerResponse, TransferOffer } from '../../api/types'

// Display order for the roster: goalkeepers, then defenders, midfielders, attackers.
const POSITION_ORDER: Record<PlayerResponse['position'], number> = { GK: 0, DEF: 1, MID: 2, ATT: 3 }

function positionLabel(pos: PlayerResponse['position']): string {
  return { GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }[pos]
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
                    <Chip size="small" label={positionLabel(player.position)} />
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
                    <TableCell align="center">{positionLabel(player.position)}</TableCell>
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
