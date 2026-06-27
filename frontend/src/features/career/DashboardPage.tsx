import { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { createCareer, getSaveGame, getFinances } from '../../api/career'
import { formatBRL } from '../../lib/money'
import { AdvancePanel } from '../match/AdvancePanel'
import type { SaveGameResponse } from '../../api/types'

// Scope the stored save game per user so one account never inherits another's
// career id (which would 404 against the ownership guard).
const saveGameKeyFor = (userId: string | undefined) =>
  `elifoot_save_game_id_${userId ?? 'anon'}`

export function DashboardPage() {
  const { user, token, logout } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const saveGameKey = saveGameKeyFor(user?.userId)
  const [saveGameId, setSaveGameId] = useState<string | null>(() =>
    localStorage.getItem(saveGameKey),
  )

  const handleLogout = () => {
    logout()
    void navigate('/login')
  }

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

  const createMutation = useMutation<SaveGameResponse>({
    mutationFn: () => createCareer(token ?? ''),
    onSuccess: (data) => {
      localStorage.setItem(saveGameKey, data.id)
      setSaveGameId(data.id)
      void queryClient.invalidateQueries({ queryKey: ['saveGame', data.id] })
    },
  })

  const squadUrl = saveGame
    ? `/squad?saveGameId=${saveGame.id}&clubId=${saveGame.managedClubId}`
    : null

  const tableUrl = saveGame
    ? `/table?saveGameId=${saveGame.id}&competitionId=${saveGame.competitionId}&managedClubId=${saveGame.managedClubId}`
    : null

  return (
    <div>
      <header className="cec-header">
        <h1 style={{ margin: 0, fontSize: '1.25rem' }}>Elifoot Carvoeiro</h1>
      </header>
      <main style={{ padding: '2rem' }}>
        <h2>Bem-vindo, {user?.username}</h2>

        {!saveGameId && !createMutation.isPending && (
          <button className="cec-btn" onClick={() => createMutation.mutate()}>
            Nova carreira
          </button>
        )}

        {createMutation.isPending && <p>Criando carreira…</p>}
        {createMutation.isError && <p style={{ color: 'red' }}>Erro ao criar carreira.</p>}

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

            <div style={{ display: 'flex', gap: '1rem', marginTop: '1rem' }}>
              {squadUrl && (
                <Link to={squadUrl}>
                  <button className="cec-btn">Escalação</button>
                </Link>
              )}
              {tableUrl && (
                <Link to={tableUrl}>
                  <button className="cec-btn">Classificação</button>
                </Link>
              )}
              <Link to={`/history?saveGameId=${saveGame.id}`}>
                <button className="cec-btn">Histórico</button>
              </Link>
              <Link to={`/finances?saveGameId=${saveGame.id}&clubId=${saveGame.managedClubId}`}>
                <button className="cec-btn">Finanças</button>
              </Link>
              <Link to={`/transfers?saveGameId=${saveGame.id}&clubId=${saveGame.managedClubId}`}>
                <button className="cec-btn">Mercado</button>
              </Link>
            </div>

            <AdvancePanel
              saveGameId={saveGame.id}
              competitionId={saveGame.competitionId}
              managedClubId={saveGame.managedClubId}
            />
          </div>
        )}

        <div style={{ marginTop: '2rem' }}>
          <button className="cec-btn" onClick={handleLogout}>
            Sair
          </button>
        </div>
      </main>
    </div>
  )
}
