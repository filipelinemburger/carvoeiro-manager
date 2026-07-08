import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  Box, Card, CardContent, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { getClubs } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { ClubSummary } from '../../api/types'

const MANAGED_BG = 'rgba(0, 100, 200, 0.1)'

/** The club's kit colors as a small two-tone badge. */
function KitBadge({ club }: { club: ClubSummary }) {
  return (
    <Box
      component="span"
      aria-hidden
      sx={{
        display: 'inline-block',
        width: 14,
        height: 14,
        borderRadius: '50%',
        border: '1px solid rgba(0,0,0,0.25)',
        background: `linear-gradient(90deg, ${club.primaryColor ?? '#888'} 50%, ${club.secondaryColor ?? '#ccc'} 50%)`,
        verticalAlign: 'middle',
      }}
    />
  )
}

/**
 * Club browsing: every club of the save game sorted by reputation (highest first).
 * Clicking a club opens its read-only roster at /club.
 */
export function ClubsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const isMobile = useIsMobile()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const managedClubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['clubs', saveGameId],
    queryFn: () => getClubs(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  const clubs = data ?? []

  const openClub = (club: ClubSummary) => {
    void navigate(`/club?saveGameId=${saveGameId}&clubId=${club.id}`)
  }

  return (
    <Box>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
        Clubes
      </Typography>

      {isLoading && <Typography sx={{ mt: 2 }}>Carregando clubes…</Typography>}
      {error && <Typography sx={{ mt: 2 }} color="error">Erro ao carregar clubes.</Typography>}

      {!isLoading && !error && (
        isMobile ? (
          <Stack spacing={1.5} sx={{ mt: 2 }}>
            {clubs.map(club => {
              const managed = club.id === managedClubId
              return (
                <Card
                  key={club.id}
                  variant="outlined"
                  data-managed={managed ? 'true' : undefined}
                  onClick={() => openClub(club)}
                  sx={{
                    cursor: 'pointer',
                    backgroundColor: managed ? MANAGED_BG : undefined,
                    '&:hover': { borderColor: 'primary.main' },
                  }}
                >
                  <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <KitBadge club={club} />
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography sx={{ fontWeight: managed ? 800 : 600 }}>
                          {club.commonName ?? club.name}
                        </Typography>
                        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                          Reputação {club.reputation} · Elenco {club.squadSize}
                        </Typography>
                      </Box>
                    </Stack>
                  </CardContent>
                </Card>
              )
            })}
          </Stack>
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>CLUBE</TableCell>
                  <TableCell align="center">REPUTAÇÃO</TableCell>
                  <TableCell align="center">ELENCO</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {clubs.map(club => {
                  const managed = club.id === managedClubId
                  return (
                    <TableRow
                      key={club.id}
                      hover
                      data-managed={managed ? 'true' : undefined}
                      onClick={() => openClub(club)}
                      sx={{
                        cursor: 'pointer',
                        backgroundColor: managed ? MANAGED_BG : undefined,
                        '& td': { fontWeight: managed ? 700 : undefined },
                      }}
                    >
                      <TableCell>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          <KitBadge club={club} />
                          <span>{club.commonName ?? club.name}</span>
                        </Stack>
                      </TableCell>
                      <TableCell align="center" sx={{ fontVariantNumeric: 'tabular-nums' }}>{club.reputation}</TableCell>
                      <TableCell align="center" sx={{ fontVariantNumeric: 'tabular-nums' }}>{club.squadSize}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )
      )}
    </Box>
  )
}
