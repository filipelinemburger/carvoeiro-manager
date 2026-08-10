import { Box, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import type { StandingRow } from '../../api/types'

// Background tint for the managed club's standings row (matches TablePage's highlight).
const MANAGED_BG = 'rgba(0, 100, 200, 0.1)'

/**
 * "Classificação antes da rodada" — pre-round standings snapshot, moved verbatim out of
 * `LiveMatchPage` (the old Classificação tab). The query itself (`standingsQuery`) stays in the
 * container — see the comment on it in `LiveMatchPage.tsx` for why — this component only
 * renders the `{ isLoading, data }` result handed down to it.
 */
export function LiveStandingsTable({ isLoading, data, managedClubId }: {
  isLoading: boolean
  data: StandingRow[] | undefined
  managedClubId: string | undefined
}) {
  return (
    <Box sx={{ mt: 1 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>Classificação antes da rodada</Typography>
      {isLoading ? (
        <Typography sx={{ mt: 1, color: 'text.secondary' }}>Carregando classificação…</Typography>
      ) : (data ?? []).length === 0 ? (
        <Typography sx={{ mt: 1, color: 'text.secondary' }}>Classificação indisponível.</Typography>
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 1 }}>
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
              {(data ?? []).map((row, index) => {
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
    </Box>
  )
}
