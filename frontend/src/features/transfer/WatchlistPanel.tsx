import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Box, Button, Chip, Link, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography,
} from '@mui/material'
import { getWatchlist, removeFromWatchlist } from '../../api/watchlist'
import { getPlayer } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'

type Props = {
  saveGameId: string
  token: string
}

/**
 * The Observados list: rival players the manager has starred from their details dialog.
 * Read-only besides "Remover" — adding happens via the star toggle in PlayerDetails.
 */
export default function WatchlistPanel({ saveGameId, token }: Props) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  // The watched player whose full read-only details (+ watch star) are open, if any.
  const [selectedPlayerId, setSelectedPlayerId] = useState<string | null>(null)
  const enabled = Boolean(saveGameId && token)

  const watchlist = useQuery({
    queryKey: ['watchlist', saveGameId],
    queryFn: () => getWatchlist(saveGameId, token),
    enabled,
    staleTime: Infinity,
  })

  const selectedPlayer = useQuery({
    queryKey: ['player', saveGameId, selectedPlayerId],
    queryFn: () => getPlayer(saveGameId, selectedPlayerId!, token),
    enabled: enabled && Boolean(selectedPlayerId),
  })

  const unwatchMut = useMutation({
    mutationFn: (playerId: string) => removeFromWatchlist(saveGameId, playerId, token),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['watchlist', saveGameId] }),
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao remover da lista.')),
  })

  if (watchlist.isLoading) return <Typography color="text.secondary">Carregando…</Typography>
  if (watchlist.isError) return <Alert severity="error">Erro ao carregar a lista de observados.</Alert>

  const rows = watchlist.data ?? []

  return (
    <Box>
      {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>{error}</Alert>}
      {rows.length === 0 ? (
        <Typography color="text.secondary">
          Nenhum jogador observado. Abra os detalhes de um jogador de outro clube e toque na estrela.
        </Typography>
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Jogador</TableCell>
              <TableCell>Pos</TableCell>
              <TableCell align="right">Idade</TableCell>
              <TableCell align="right">Geral</TableCell>
              <TableCell>Clube</TableCell>
              <TableCell>Situação</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {rows
              // Guard against a transient optimistic placeholder row: WatchStar (mounted
              // elsewhere) writes `{ playerId }` into this same cache while an add is in
              // flight, before onSettled's invalidation replaces it with the full row.
              .filter(p => Boolean(p.name))
              .map(p => (
              <TableRow key={p.playerId} hover>
                <TableCell>
                  <Link
                    component="button"
                    type="button"
                    underline="hover"
                    color="inherit"
                    onClick={() => setSelectedPlayerId(p.playerId)}
                    sx={{ font: 'inherit', textAlign: 'left', cursor: 'pointer' }}
                  >
                    {p.name}
                  </Link>
                </TableCell>
                <TableCell>{p.position}</TableCell>
                <TableCell align="right">{p.age}</TableCell>
                <TableCell align="right">{p.overall}</TableCell>
                <TableCell>{p.clubName}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    {p.transferListed && <Chip size="small" color="warning" label="Listado" />}
                    {p.preContractEligible && <Chip size="small" color="info" label="Pré-contrato" />}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Button size="small" disabled={unwatchMut.isPending}
                          onClick={() => unwatchMut.mutate(p.playerId)}>
                    Remover
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <PlayerDetails
        player={selectedPlayer.data ?? null}
        onClose={() => setSelectedPlayerId(null)}
        saveGameId={saveGameId}
        isOwnPlayer={false}
        watchAction={selectedPlayer.data ? (
          <WatchStar saveGameId={saveGameId} playerId={selectedPlayer.data.id} />
        ) : undefined}
      />
    </Box>
  )
}
