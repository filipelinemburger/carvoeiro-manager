import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { createCareer, getSaveGame } from '../../api/career'
import { AdvancePanel } from '../match/AdvancePanel'
import type { SaveGameResponse } from '../../api/types'

const SAVE_GAME_KEY = 'elifoot_save_game_id'

export function DashboardPage() {
  const { user, token, logout } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const storedId = localStorage.getItem(SAVE_GAME_KEY)
  const [saveGameId, setSaveGameId] = useState<string | null>(storedId)

  const handleLogout = () => {
    logout()
    void navigate('/login')
  }

  const { data: saveGame, isLoading: loadingGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  const createMutation = useMutation<SaveGameResponse>({
    mutationFn: () => createCareer(token ?? ''),
    onSuccess: (data) => {
      localStorage.setItem(SAVE_GAME_KEY, data.id)
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
            </div>

            <AdvancePanel saveGameId={saveGame.id} competitionId={saveGame.competitionId} />
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
