import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { getSaveGame, getFinances } from '../../api/career'
import { formatBRL } from '../../lib/money'
import type { SaveGameResponse } from '../../api/types'

// Scope the stored save game per user so one account never inherits another's
// career id (which would 404 against the ownership guard).
const saveGameKeyFor = (userId: string | undefined) =>
  `elifoot_save_game_id_${userId ?? 'anon'}`

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
    <div>
      <h2>Bem-vindo, {user?.username}</h2>

      {!saveGameId && (
        <p style={{ color: '#555' }}>
          Você ainda não tem uma carreira. Abra o menu <strong>⚙️</strong> no canto superior
          direito e escolha <strong>Nova Carreira</strong> para começar.
        </p>
      )}

      {loadingGame && saveGameId && <p>Carregando carreira…</p>}

      {saveGame && (
        <div style={{ marginTop: '1.5rem' }}>
          <p>
            <strong>Temporada:</strong> {saveGame.seasonYear}
          </p>
          <p>
            <strong>Competição:</strong> {saveGame.competitionName}
          </p>
          <p>
            <strong>Clube:</strong> {saveGame.managedClubName}
          </p>
          <p>
            <strong>Rodada:</strong> {saveGame.currentRound} / {saveGame.totalRounds}
          </p>
          {finances && (
            <p style={{ color: finances.balance < 0 ? 'red' : undefined }}>
              <strong>Saldo:</strong> {formatBRL(finances.balance)}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
