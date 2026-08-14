import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, Button, Card, CardContent, Chip, List, ListItem, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getSeasonHistory, getSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'

export function SeasonHistoryPage() {
  const { token } = useAuth()
  const isMobile = useIsMobile()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [selectedYear, setSelectedYear] = useState<number | null>(null)

  const history = useQuery({
    queryKey: ['seasonHistory', saveGameId],
    queryFn: () => getSeasonHistory(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const detail = useQuery({
    queryKey: ['season', saveGameId, selectedYear],
    queryFn: () => getSeason(saveGameId, selectedYear!, token ?? ''),
    enabled: Boolean(saveGameId && token && selectedYear !== null),
    staleTime: Infinity,
  })

  if (history.isLoading) return <Typography sx={{ p: 3 }}>Carregando histórico…</Typography>
  if (history.error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar o histórico.</Typography>

  const seasons = history.data ?? []

  // A background competition (Série C for a Catarinense/Série B career) is archived for its
  // champion, but the player has no placing in it — the API sends null, and a dash is the honest
  // rendering. `${null}º` would read "nullº".
  const positionLabel = (position: number | null) => (position === null ? '—' : `${position}º`)

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Histórico de Temporadas</Typography>
      {seasons.length === 0 && <Typography sx={{ mb: 2 }}>Nenhuma temporada concluída ainda.</Typography>}

      {isMobile ? (
        <Stack spacing={1} sx={{ mb: 3 }}>
          {seasons.map(s => (
            <Card key={`${s.seasonYear}-${s.competitionName}`} variant="outlined">
              <CardContent>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                  <Typography variant="h6" sx={{ fontWeight: 700 }}>{s.seasonYear}</Typography>
                  <Chip size="small" label={positionLabel(s.managedClubPosition)} />
                  <Typography variant="body2" color="text.secondary">{s.competitionName}</Typography>
                </Stack>
                <Typography variant="body2">Campeão: {s.championClubName}</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  Artilheiro: {s.topScorerName ? `${s.topScorerName} (${s.topScorerGoals})` : '—'}
                </Typography>
                <Button size="small" variant="outlined" onClick={() => setSelectedYear(s.seasonYear)}>
                  Ver {s.seasonYear}
                </Button>
              </CardContent>
            </Card>
          ))}
        </Stack>
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mb: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Ano</TableCell>
                <TableCell>Competição</TableCell>
                <TableCell>Campeão</TableCell>
                <TableCell align="center">Sua posição</TableCell>
                <TableCell>Artilheiro</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {seasons.map(s => (
                <TableRow key={`${s.seasonYear}-${s.competitionName}`}>
                  <TableCell>{s.seasonYear}</TableCell>
                  <TableCell>{s.competitionName}</TableCell>
                  <TableCell>{s.championClubName}</TableCell>
                  <TableCell align="center">{positionLabel(s.managedClubPosition)}</TableCell>
                  <TableCell>{s.topScorerName ? `${s.topScorerName} (${s.topScorerGoals})` : '—'}</TableCell>
                  <TableCell>
                    <Button size="small" variant="outlined" onClick={() => setSelectedYear(s.seasonYear)}>
                      Ver {s.seasonYear}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {selectedYear !== null && detail.isLoading && (
        <Typography>Carregando temporada {selectedYear}…</Typography>
      )}
      {detail.data && (
        <Box>
          <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>
            {detail.data.competitionName} {detail.data.seasonYear}
          </Typography>
          {isMobile ? (
            <Stack spacing={1} sx={{ mb: 3 }}>
              {detail.data.table.map(row => (
                <Card key={row.position} variant="outlined">
                  <CardContent>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                      <Chip size="small" label={`${row.position}º`} />
                      <Typography sx={{ fontWeight: 600 }}>{row.clubName}</Typography>
                    </Stack>
                    <Stack direction="row" spacing={2}>
                      <Typography variant="body2" color="text.secondary">J {row.played}</Typography>
                      <Typography variant="body2" color="text.secondary">SG {row.goalDifference}</Typography>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>Pts {row.points}</Typography>
                    </Stack>
                  </CardContent>
                </Card>
              ))}
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
                  {detail.data.table.map(row => (
                    <TableRow key={row.position}>
                      <TableCell align="center">{row.position}</TableCell>
                      <TableCell>{row.clubName}</TableCell>
                      <TableCell align="center">{row.played}</TableCell>
                      <TableCell align="center">{row.goalDifference}</TableCell>
                      <TableCell align="center">{row.points}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 0.5 }}>Artilheiros</Typography>
          <List dense disablePadding>
            {detail.data.topScorers.map((s, i) => (
              <ListItem key={`${s.playerName}-${i}`} disableGutters sx={{ py: 0.25 }}>
                <Typography variant="body2">{s.playerName} ({s.clubName}) — {s.goals}</Typography>
              </ListItem>
            ))}
          </List>
        </Box>
      )}
    </Box>
  )
}
