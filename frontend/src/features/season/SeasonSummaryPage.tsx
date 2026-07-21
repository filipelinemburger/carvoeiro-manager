import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import {
  Box, Button, Card, CardContent, Chip, List, ListItem, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getStandings, getTopScorers, rolloverSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { RolloverResponse, SquadChange } from '../../api/types'

/** A titled block listing squad changes (retirements, expired contracts, youth intake/departures). */
function SquadChangeList({ title, changes }: { title: string; changes: SquadChange[] }) {
  return (
    <Box>
      <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 0.5 }}>{title}</Typography>
      <List dense disablePadding>
        {changes.map((c, i) => (
          <ListItem key={`${title}-${i}`} disableGutters sx={{ py: 0.25 }}>
            <Typography variant="body2">{c.playerName} ({c.position}, {c.age} anos)</Typography>
          </ListItem>
        ))}
      </List>
    </Box>
  )
}

export function SeasonSummaryPage() {
  const { token } = useAuth()
  const isMobile = useIsMobile()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const competitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  const standings = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
    staleTime: Infinity,
  })

  const scorers = useQuery({
    queryKey: ['topScorers', saveGameId, competitionId],
    queryFn: () => getTopScorers(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const rollover = useMutation<RolloverResponse>({
    mutationFn: () => rolloverSeason(saveGameId, token ?? ''),
    onSuccess: () => {
      // Aging, retirements, contract expiry, youth intake/departures, and free-agent pool
      // top-up all happen here — this is the one place that must tell every affected cache
      // it's stale now (under staleTime: Infinity nothing else will).
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
      void queryClient.invalidateQueries({ queryKey: ['youth', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['seasonHistory', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['clubs', saveGameId] })
      // Rollover executes agreed pre-contract deals (arrivals/departures) — both lists go stale.
      void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
      // Rollover can also sell/list/free watched players during the off-season.
      void queryClient.invalidateQueries({ queryKey: ['watchlist', saveGameId] })
    },
  })

  if (standings.isLoading) return <Typography sx={{ p: 3 }}>Carregando resumo da temporada…</Typography>
  if (standings.error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar o resumo da temporada.</Typography>

  const table = standings.data ?? []
  const champion = table[0]
  const result = rollover.data

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Fim de Temporada</Typography>

      {champion && (
        <Typography variant="h6" sx={{ fontWeight: 700, color: 'success.main', mb: 2 }}>
          Campeão: {champion.clubName} 🏆
        </Typography>
      )}

      <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>Classificação Final</Typography>
      {isMobile ? (
        <Stack spacing={1} sx={{ mb: 3 }}>
          {table.map((row, index) => {
            const isManaged = row.clubId === managedClubId
            return (
              <Card key={row.clubId} variant="outlined" data-managed={isManaged ? 'true' : undefined}
                sx={{ borderColor: isManaged ? 'primary.main' : undefined }}>
                <CardContent>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                    <Chip size="small" label={`${index + 1}º`} />
                    <Typography sx={{ fontWeight: isManaged ? 700 : 600 }}>{row.clubName}</Typography>
                  </Stack>
                  <Stack direction="row" spacing={2}>
                    <Typography variant="body2" color="text.secondary">J {row.played}</Typography>
                    <Typography variant="body2" color="text.secondary">SG {row.goalDifference}</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>Pts {row.points}</Typography>
                  </Stack>
                </CardContent>
              </Card>
            )
          })}
        </Stack>
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mb: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell align="center">Pos</TableCell>
                <TableCell>Clube</TableCell>
                <TableCell align="center">P</TableCell>
                <TableCell align="center">GD</TableCell>
                <TableCell align="center">Pts</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {table.map((row, index) => {
                const isManaged = row.clubId === managedClubId
                return (
                  <TableRow key={row.clubId} data-managed={isManaged ? 'true' : undefined}
                    sx={{ '& td': { fontWeight: isManaged ? 700 : undefined } }}>
                    <TableCell align="center">{index + 1}</TableCell>
                    <TableCell>{row.clubName}</TableCell>
                    <TableCell align="center">{row.played}</TableCell>
                    <TableCell align="center">{row.goalDifference}</TableCell>
                    <TableCell align="center">{row.points}</TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>Artilheiros</Typography>
      <List dense sx={{ mb: 2 }}>
        {(scorers.data ?? []).map((s, i) => (
          <ListItem key={`${s.playerName}-${i}`} disableGutters sx={{ py: 0.25 }}>
            <Typography variant="body2">{s.playerName} ({s.clubName}) — {s.goals}</Typography>
          </ListItem>
        ))}
      </List>

      {!result && (
        <Button
          variant="contained"
          disabled={rollover.isPending}
          onClick={() => rollover.mutate()}
        >
          {rollover.isPending ? 'Processando…' : 'Iniciar próxima temporada'}
        </Button>
      )}
      {rollover.isError && (
        <Typography color="error" sx={{ mt: 1 }}>Erro ao iniciar a próxima temporada.</Typography>
      )}

      {result && (
        <Stack spacing={2} sx={{ mt: 2 }}>
          <Typography sx={{ color: 'success.main', fontWeight: 600 }}>
            Temporada {result.seasonYear} iniciada!
          </Typography>
          {result.retirements.length > 0 && (
            <SquadChangeList title="Aposentadorias" changes={result.retirements} />
          )}
          {(result.expiredContracts?.length ?? 0) > 0 && (
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>Contratos expirados</Typography>
              <List dense disablePadding>
                {result.expiredContracts.map((c, i) => (
                  <ListItem key={i} disableGutters sx={{ py: 0.25 }}>
                    <Typography variant="body2">{c.playerName} ({c.position}, {c.age})</Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}
          {((result.preContractArrivals ?? []).length > 0) && (
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>Chegadas por pré-contrato</Typography>
              <List dense disablePadding>
                {(result.preContractArrivals ?? []).map((c, i) => (
                  <ListItem key={i} disableGutters sx={{ py: 0.25 }}>
                    <Typography variant="body2">{c.playerName} ({c.position}, {c.age})</Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}
          {((result.preContractDepartures ?? []).length > 0) && (
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>Saídas por pré-contrato</Typography>
              <List dense disablePadding>
                {(result.preContractDepartures ?? []).map((c, i) => (
                  <ListItem key={i} disableGutters sx={{ py: 0.25 }}>
                    <Typography variant="body2">{c.playerName} ({c.position}, {c.age})</Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}
          {((result.watchlistEvents ?? []).length > 0) && (
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>Observados que ficaram livres</Typography>
              <List dense disablePadding>
                {(result.watchlistEvents ?? []).map((a, i) => (
                  <ListItem key={i} disableGutters sx={{ py: 0.25 }}>
                    <Typography variant="body2">{a.playerName} (deixou {a.clubName})</Typography>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}
          {result.youthIntake.length > 0 && (
            <SquadChangeList title="Reforços da Base" changes={result.youthIntake} />
          )}
          {result.youthDepartures.length > 0 && (
            <SquadChangeList title="Saídas da Base" changes={result.youthDepartures} />
          )}
          <Box>
            <Button component={Link} to="/dashboard" variant="outlined">Voltar ao painel</Button>
          </Box>
        </Stack>
      )}
    </Box>
  )
}
