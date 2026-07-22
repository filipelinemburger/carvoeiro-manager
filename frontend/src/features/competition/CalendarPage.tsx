import { useQueries, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getClubs, getFixtures, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatDatePtBR } from '../../utils/date'
import type { ClubSummary, FixtureEntry, SaveGameResponse } from '../../api/types'

const NEXT_UNPLAYED_BG = 'rgba(0, 100, 200, 0.1)'
const STADIUM_PREFIX = 'Estádio '
const COMPETITION_PREFIX = 'Campeonato '

/** A fixture merged with the name of the competition it belongs to. */
type CalendarFixture = FixtureEntry & { competitionName: string }

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

/** Strips the leading "Estádio " prefix for a more compact display. */
function stadiumDisplayName(stadiumName: string): string {
  return stadiumName.startsWith(STADIUM_PREFIX) ? stadiumName.slice(STADIUM_PREFIX.length) : stadiumName
}

/** Strips the leading "Campeonato " prefix, e.g. "Campeonato Catarinense" -> "Catarinense". */
function competitionDisplayName(competitionName: string): string {
  return competitionName.startsWith(COMPETITION_PREFIX)
    ? competitionName.slice(COMPETITION_PREFIX.length)
    : competitionName
}

/** True home-vs-away matchup with short club names, e.g. "Criciúma x Chapecoense". */
function matchupLabel(fixture: FixtureEntry, managedClubName: string, clubNameById: Map<string, string>): string {
  const opponentName = clubNameById.get(fixture.opponentClubId) ?? fixture.opponentName
  return fixture.home
    ? `${managedClubName} x ${opponentName}`
    : `${opponentName} x ${managedClubName}`
}

/** The "2x0" / "—" score cell in true home/away order, colored by the managed club's outcome. */
function ScoreCell({ fixture }: { fixture: FixtureEntry }) {
  const result = resultOf(fixture)
  if (!result || fixture.goalsFor == null || fixture.goalsAgainst == null) {
    return <TableCell align="center">—</TableCell>
  }
  const [homeGoals, awayGoals] = fixture.home
    ? [fixture.goalsFor, fixture.goalsAgainst]
    : [fixture.goalsAgainst, fixture.goalsFor]
  return (
    <TableCell align="center">
      <Typography
        component="span"
        data-result={result}
        sx={{ color: RESULT_COLOR[result], fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}
      >
        {homeGoals}x{awayGoals}
      </Typography>
    </TableCell>
  )
}

export function CalendarPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()

  const saveGameId = searchParams.get('saveGameId') ?? ''

  const { data: saveGame, isLoading: isSaveGameLoading, error: saveGameError } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const competitions = saveGame?.competitions ?? []

  // Club list gives the short/common names ("Criciúma", "Barra") used in the matchup column;
  // any club missing from it falls back to the full name carried by the fixture itself.
  const { data: clubs, isLoading: isClubsLoading } = useQuery<ClubSummary[]>({
    queryKey: ['clubs', saveGameId],
    queryFn: () => getClubs(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const clubNameById = new Map((clubs ?? []).map(club => [club.id, club.commonName ?? club.name]))
  const managedClubName = clubNameById.get(saveGame?.managedClubId ?? '') ?? saveGame?.managedClubName ?? ''

  // Fetch the managed club's fixtures across every competition in the save, then merge into one calendar.
  const fixtureQueries = useQueries({
    queries: competitions.map(competition => ({
      queryKey: ['fixtures', saveGameId, competition.id],
      queryFn: () => getFixtures(saveGameId, competition.id, token ?? ''),
      enabled: Boolean(saveGameId && competition.id && token),
      staleTime: Infinity,
    })),
  })

  const isFixturesLoading = fixtureQueries.some(q => q.isLoading)
  const fixturesError = fixtureQueries.find(q => q.error)?.error

  const fixtures: CalendarFixture[] = fixtureQueries
    .flatMap((q, index) => (q.data ?? []).map(fixture => ({ ...fixture, competitionName: competitions[index].name })))
    .sort((a, b) => a.matchDate.localeCompare(b.matchDate))

  const nextUnplayedFixtureId = fixtures.find(f => !f.played)?.fixtureId

  const isLoading = isSaveGameLoading || isClubsLoading || isFixturesLoading
  const error = saveGameError || fixturesError

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
      </Stack>

      {isLoading && <Typography sx={{ mt: 2 }}>Carregando calendário…</Typography>}
      {error != null && <Typography sx={{ mt: 2 }} color="error">Erro ao carregar calendário.</Typography>}

      {!isLoading && error == null && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 2, overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell align="center">DATA</TableCell>
                <TableCell>CAMPEONATO</TableCell>
                <TableCell>JOGO</TableCell>
                <TableCell>ESTÁDIO</TableCell>
                <TableCell align="center">PLACAR</TableCell>
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
                    <TableCell align="center">{formatDatePtBR(fixture.matchDate)}</TableCell>
                    <TableCell>{competitionDisplayName(fixture.competitionName)}</TableCell>
                    <TableCell>{matchupLabel(fixture, managedClubName, clubNameById)}</TableCell>
                    <TableCell>{stadiumDisplayName(fixture.stadiumName)}</TableCell>
                    <ScoreCell fixture={fixture} />
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
