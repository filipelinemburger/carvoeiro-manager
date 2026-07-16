import { useEffect, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Box, Button, Card, CardContent, Stack, Typography } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getSaveGame, getFinances, listCareers } from '../../api/career'
import { saveGameKeyFor } from './saveGameKey'
import { formatBRL } from '../../lib/money'
import type { SaveGameResponse, SaveGameSummary } from '../../api/types'

/** A single "Label: value" stat row with a bold label. */
function StatRow({ label, value, color }: { label: string; value: ReactNode; color?: string }) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={{ xs: 0.25, sm: 1 }}
      sx={{ alignItems: { sm: 'baseline' }, color }}
    >
      <Typography component="span" sx={{ fontWeight: 700 }}>
        {label}
      </Typography>
      <Typography component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Typography>
    </Stack>
  )
}

export function DashboardPage() {
  const { user, token } = useAuth()

  const saveGameKey = saveGameKeyFor(user?.userId)
  const [saveGameId, setSaveGameId] = useState<string | null>(() =>
    localStorage.getItem(saveGameKey),
  )

  const { data: saveGame, isLoading: loadingGame, isError } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
  })

  const { data: finances } = useQuery({
    queryKey: ['finances', saveGame?.id, saveGame?.managedClubId],
    queryFn: () => getFinances(saveGame!.id, saveGame!.managedClubId, token ?? ''),
    enabled: Boolean(saveGame && token),
  })

  // Only needed to offer "Carregar última carreira" while there's no active career.
  const { data: careers } = useQuery<SaveGameSummary[]>({
    queryKey: ['careers'],
    queryFn: () => listCareers(token ?? ''),
    enabled: Boolean(!saveGameId && token),
  })

  // If the stored save game can't be loaded (deleted, or not owned by this
  // user), forget it so the user can start a fresh career instead of being
  // stuck on a 404.
  useEffect(() => {
    if (isError && saveGameId) {
      localStorage.removeItem(saveGameKey)
      setSaveGameId(null)
    }
  }, [isError, saveGameId, saveGameKey])

  const loadLatest = () => {
    if (careers && careers.length > 0) {
      // Deliberately not resumeCareer() here: DashboardPage is already mounted at
      // /dashboard when this button is clicked, so navigate('/dashboard') wouldn't
      // remount it, and saveGameId (seeded once via useState) would never pick up
      // the localStorage change. Update the state directly instead.
      localStorage.setItem(saveGameKey, careers[0].id)
      setSaveGameId(careers[0].id)
    }
  }

  return (
    <Box sx={{ p: { xs: 2, sm: 3 } }}>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
        Bem-vindo, {user?.username}
      </Typography>

      {!saveGameId && (
        <>
          <Typography sx={{ mt: 2, color: 'text.secondary', maxWidth: 640 }}>
            Você ainda não tem uma carreira. Abra o menu <Box component="strong">⚙️</Box> no canto
            superior direito e escolha <Box component="strong">Nova Carreira</Box> para começar.
          </Typography>
          {careers && careers.length > 0 && (
            <Button variant="contained" sx={{ mt: 2 }} onClick={loadLatest}>
              Carregar última carreira
            </Button>
          )}
        </>
      )}

      {loadingGame && saveGameId && <Typography sx={{ mt: 2 }}>Carregando carreira…</Typography>}

      {saveGame && (
        <Card variant="outlined" sx={{ mt: 3, maxWidth: 480 }}>
          <CardContent>
            <Stack spacing={1.25}>
              {saveGame.managerName && (
                <StatRow label="Técnico:" value={`${saveGame.managerName} (${saveGame.managerNationality})`} />
              )}
              <StatRow label="Temporada:" value={saveGame.seasonYear} />
              <StatRow label="Competição:" value={saveGame.competitionName} />
              <StatRow label="Clube:" value={saveGame.managedClubName} />
              <StatRow
                label="Rodada:"
                value={`${saveGame.currentRound} / ${saveGame.totalRounds}`}
              />
              {finances && (
                <StatRow
                  label="Saldo:"
                  value={formatBRL(finances.balance)}
                  color={finances.balance < 0 ? 'error.main' : undefined}
                />
              )}
            </Stack>
          </CardContent>
        </Card>
      )}
    </Box>
  )
}
