import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, NativeSelect, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getFixtures, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatDatePtBR } from '../../utils/date'
import type { FixtureEntry, SaveGameResponse } from '../../api/types'

const NEXT_UNPLAYED_BG = 'rgba(0, 100, 200, 0.1)'

/** Managed-club outcome derived from the goals-for/against already oriented by the backend. */
type Result = 'WIN' | 'DRAW' | 'LOSS'

function resultOf(fixture: FixtureEntry): Result | null {
  if (!fixture.played || fixture.goalsFor == null || fixture.goalsAgainst == null) return null
  if (fixture.goalsFor > fixture.goalsAgainst) return 'WIN'
  if (fixture.goalsFor < fixture.goalsAgainst) return 'LOSS'
  return 'DRAW'
}

const RESULT_COLOR: Record<Result, string> = {
  WIN: 'success.main',
  LOSS: 'error.main',
  DRAW: 'text.primary',
}

/** The "3–1" / "—" result cell, colored by the managed club's outcome. */
function ResultCell({ fixture }: { fixture: FixtureEntry }) {
  const result = resultOf(fixture)
  if (!result) {
    return <TableCell align="center">—</TableCell>
  }
  return (
    <TableCell align="center">
      <Typography
        component="span"
        data-result={result}
        sx={{ color: RESULT_COLOR[result], fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}
      >
        {fixture.goalsFor}–{fixture.goalsAgainst}
      </Typography>
    </TableCell>
  )
}

export function CalendarPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const paramCompetitionId = searchParams.get('competitionId') ?? ''

  // The competition can be switched in the UI; it starts at the one the nav linked to.
  const [selectedCompetitionId, setSelectedCompetitionId] = useState(paramCompetitionId)
  const competitionId = selectedCompetitionId || paramCompetitionId

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const { data, isLoading, error } = useQuery({
    queryKey: ['fixtures', saveGameId, competitionId],
    queryFn: () => getFixtures(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
    staleTime: Infinity,
  })

  const competitions = saveGame?.competitions ?? []
  const fixtures = data ?? []
  const nextUnplayedFixtureId = fixtures.find(f => !f.played)?.fixtureId

  return (
    <Box>
      <Stack
        direction="row"
        spacing={2}
        useFlexGap
        sx={{ alignItems: 'center', flexWrap: 'wrap' }}
      >
        <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
          Calendário
        </Typography>
        {competitions.length > 1 && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Typography component="span" variant="body2">Campeonato:</Typography>
            <NativeSelect
              value={competitionId}
              onChange={e => setSelectedCompetitionId(e.target.value)}
              inputProps={{ 'aria-label': 'Campeonato' }}
            >
              {competitions.map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
          </Box>
        )}
      </Stack>

      {isLoading && <Typography sx={{ mt: 2 }}>Carregando calendário…</Typography>}
      {error && <Typography sx={{ mt: 2 }} color="error">Erro ao carregar calendário.</Typography>}

      {!isLoading && !error && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 2, overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell align="center">RODADA</TableCell>
                <TableCell align="center">DATA</TableCell>
                <TableCell>ADVERSÁRIO</TableCell>
                <TableCell>ESTÁDIO</TableCell>
                <TableCell align="center">RESULTADO</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {fixtures.map(fixture => {
                const isNextUnplayed = fixture.fixtureId === nextUnplayedFixtureId
                return (
                  <TableRow
                    key={fixture.fixtureId}
                    data-next-unplayed={isNextUnplayed ? 'true' : undefined}
                    sx={{
                      backgroundColor: isNextUnplayed ? NEXT_UNPLAYED_BG : undefined,
                      '& td': { fontWeight: isNextUnplayed ? 700 : undefined },
                    }}
                  >
                    <TableCell align="center">{fixture.round}</TableCell>
                    <TableCell align="center">{formatDatePtBR(fixture.matchDate)}</TableCell>
                    <TableCell>{fixture.opponentShortName} {fixture.home ? '(C)' : '(F)'}</TableCell>
                    <TableCell>{fixture.stadiumName}</TableCell>
                    <ResultCell fixture={fixture} />
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}
