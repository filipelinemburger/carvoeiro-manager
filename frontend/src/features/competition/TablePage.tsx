import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, Collapse, IconButton, NativeSelect, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore'
import { getStandings, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { SaveGameResponse, StandingRow } from '../../api/types'

const MANAGED_BG = 'rgba(0, 100, 200, 0.1)'

/** A compact, centered "LABEL / value" pair sized so all stats fit on one row. */
function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Box sx={{ textAlign: 'center' }}>
      <Typography
        variant="caption"
        sx={{ display: 'block', textTransform: 'uppercase', letterSpacing: '0.02em', color: 'text.secondary', fontSize: '0.6rem', lineHeight: 1.2 }}
      >
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', fontSize: '0.8rem' }}>{value}</Typography>
    </Box>
  )
}

/** What each standings abbreviation means (pt-BR), shown as a legend below the table. */
const STAT_LEGEND: ReadonlyArray<readonly [string, string]> = [
  ['POS', 'Posição'],
  ['CLUBE', 'Clube'],
  ['P', 'Partidas'],
  ['V', 'Vitórias'],
  ['E', 'Empates'],
  ['D', 'Derrotas'],
  ['GP', 'Gols pró'],
  ['GC', 'Gols contra'],
  ['SG', 'Saldo de gols'],
  ['PTS', 'Pontos'],
]

/** A caption legend explaining the abbreviations used in the table and the detail rows. */
function StatLegend() {
  return (
    <Box sx={{ mt: 1.5, display: 'flex', flexWrap: 'wrap', columnGap: 1, rowGap: 0.25 }}>
      {STAT_LEGEND.map(([abbr, label]) => (
        <Typography key={abbr} sx={{ fontSize: '0.62rem', lineHeight: 1.4, color: 'text.secondary', whiteSpace: 'nowrap' }}>
          <Box component="span" sx={{ fontWeight: 700, color: 'text.primary' }}>{abbr}</Box> {label}
        </Typography>
      ))}
    </Box>
  )
}

export function TablePage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const isMobile = useIsMobile()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const paramCompetitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  // The competition can be switched in the UI; it starts at the one the nav linked to
  // (the latest-played-or-next competition).
  const [selectedCompetitionId, setSelectedCompetitionId] = useState(paramCompetitionId)
  const competitionId = selectedCompetitionId || paramCompetitionId

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const { data, isLoading, error } = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const competitions = saveGame?.competitions ?? []
  const rows = data ?? []

  return (
    <Box>
      <Stack
        direction="row"
        spacing={2}
        useFlexGap
        sx={{ alignItems: 'center', flexWrap: 'wrap' }}
      >
        <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
          Classificação
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

      {isLoading && <Typography sx={{ mt: 2 }}>Carregando classificação…</Typography>}
      {error && <Typography sx={{ mt: 2 }} color="error">Erro ao carregar classificação.</Typography>}

      {!isLoading && !error && (
        <>
        {isMobile ? (
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell align="center" sx={{ width: 40, py: 0, px: 0 }} aria-label="Expandir">
                    <UnfoldMoreIcon fontSize="small" sx={{ color: 'text.secondary', verticalAlign: 'middle' }} />
                  </TableCell>
                  <TableCell align="center" sx={{ px: 1 }}>POS</TableCell>
                  <TableCell align="center">CLUBE</TableCell>
                  <TableCell align="center">PTS</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row, index) => (
                  <MobileStandingRow
                    key={row.clubId}
                    row={row}
                    position={index + 1}
                    managed={row.clubId === managedClubId}
                  />
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell align="center">POS</TableCell>
                  <TableCell>CLUBE</TableCell>
                  <TableCell align="center">P</TableCell>
                  <TableCell align="center">V</TableCell>
                  <TableCell align="center">E</TableCell>
                  <TableCell align="center">D</TableCell>
                  <TableCell align="center">GP</TableCell>
                  <TableCell align="center">GC</TableCell>
                  <TableCell align="center">SG</TableCell>
                  <TableCell align="center">PTS</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row, index) => {
                  const isManaged = row.clubId === managedClubId
                  return (
                    <TableRow
                      key={row.clubId}
                      data-managed={isManaged ? 'true' : undefined}
                      sx={{
                        backgroundColor: isManaged ? MANAGED_BG : undefined,
                        '& td': { fontWeight: isManaged ? 700 : undefined },
                      }}
                    >
                      <TableCell align="center">{index + 1}</TableCell>
                      <TableCell>{row.commonName ?? row.clubName}</TableCell>
                      <TableCell align="center">{row.played}</TableCell>
                      <TableCell align="center">{row.won}</TableCell>
                      <TableCell align="center">{row.drawn}</TableCell>
                      <TableCell align="center">{row.lost}</TableCell>
                      <TableCell align="center">{row.goalsFor}</TableCell>
                      <TableCell align="center">{row.goalsAgainst}</TableCell>
                      <TableCell align="center">{row.goalDifference}</TableCell>
                      <TableCell align="center">{row.points}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        {rows.length > 0 && <StatLegend />}
        </>
      )}
    </Box>
  )
}

/**
 * A compact (mobile) standings row: position + club name and points, tappable to
 * expand a detail panel with the full stats (played, W/D/L, goals). Renders as a
 * main row plus a collapsible detail row spanning the table width.
 */
function MobileStandingRow({ row, position, managed }: { row: StandingRow; position: number; managed: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <TableRow
        data-managed={managed ? 'true' : undefined}
        hover
        onClick={() => setOpen(o => !o)}
        sx={{
          cursor: 'pointer',
          backgroundColor: managed ? MANAGED_BG : undefined,
          '& > td': { borderBottom: open ? 'unset' : undefined, fontWeight: managed ? 700 : undefined },
        }}
      >
        <TableCell align="center" sx={{ width: 40, py: 0, px: 0 }}>
          <IconButton
            size="small"
            aria-label={`${open ? 'Ocultar' : 'Ver'} detalhes de ${row.clubName}`}
          >
            {open ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
          </IconButton>
        </TableCell>
        <TableCell align="center" sx={{ px: 1, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
          {position}
        </TableCell>
        <TableCell align="center" sx={{ fontWeight: managed ? 800 : 600 }}>{row.commonName ?? row.clubName}</TableCell>
        <TableCell align="center" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>{row.points}</TableCell>
      </TableRow>
      <TableRow sx={{ backgroundColor: managed ? MANAGED_BG : undefined }}>
        <TableCell colSpan={4} sx={{ py: 0, borderBottom: open ? undefined : 'unset' }}>
          <Collapse in={open} timeout="auto" unmountOnExit>
            <Stack direction="row" sx={{ justifyContent: 'space-between', flexWrap: 'nowrap', gap: 0.5, py: 1.25 }}>
              <Stat label="P" value={row.played} />
              <Stat label="V" value={row.won} />
              <Stat label="E" value={row.drawn} />
              <Stat label="D" value={row.lost} />
              <Stat label="GP" value={row.goalsFor} />
              <Stat label="GC" value={row.goalsAgainst} />
              <Stat label="SG" value={row.goalDifference} />
            </Stack>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  )
}
