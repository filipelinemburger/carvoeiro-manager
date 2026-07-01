import { useEffect, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Box, Card, CardContent, Stack, Typography } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getSaveGame, getFinances } from '../../api/career'
import { formatBRL } from '../../lib/money'
import type { SaveGameResponse } from '../../api/types'

// Scope the stored save game per user so one account never inherits another's
// career id (which would 404 against the ownership guard).
const saveGameKeyFor = (userId: string | undefined) =>
  `carvoeiro_manager_save_game_id_${userId ?? 'anon'}`

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

  // If the stored save game can't be loaded (deleted, or not owned by this
  // user), forget it so the user can start a fresh career instead of being
  // stuck on a 404.
  useEffect(() => {
    if (isError && saveGameId) {
      localStorage.removeItem(saveGameKey)
      setSaveGameId(null)
    }
  }, [isError, saveGameId, saveGameKey])

  return (
    <Box sx={{ p: { xs: 2, sm: 3 } }}>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700 }}>
        Bem-vindo, {user?.username}
      </Typography>

      {!saveGameId && (
        <Typography sx={{ mt: 2, color: 'text.secondary', maxWidth: 640 }}>
          Você ainda não tem uma carreira. Abra o menu <Box component="strong">⚙️</Box> no canto
          superior direito e escolha <Box component="strong">Nova Carreira</Box> para começar.
        </Typography>
      )}

      {loadingGame && saveGameId && <Typography sx={{ mt: 2 }}>Carregando carreira…</Typography>}

      {saveGame && (
        <Card variant="outlined" sx={{ mt: 3, maxWidth: 480 }}>
          <CardContent>
            <Stack spacing={1.25}>
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
