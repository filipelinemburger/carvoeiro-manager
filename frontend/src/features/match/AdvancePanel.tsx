import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { advanceRound } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { SimulateRoundResponse } from '../../api/types'

interface AdvancePanelProps {
  saveGameId: string
  competitionId: string
  managedClubId: string
}

export function AdvancePanel({ saveGameId, competitionId, managedClubId }: AdvancePanelProps) {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  const mutation = useMutation<SimulateRoundResponse>({
    mutationFn: () => advanceRound(saveGameId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['standings', saveGameId, competitionId] })
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
    },
  })

  const result = mutation.data

  return (
    <div style={{ marginTop: '1.5rem' }}>
      <button
        className="cec-btn"
        disabled={mutation.isPending || result?.seasonFinished === true}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? 'Simulando…' : 'Avançar rodada'}
      </button>

      {mutation.isError && (
        <p style={{ color: 'red' }}>Erro ao avançar rodada.</p>
      )}

      {result?.seasonFinished && (
        <div style={{ marginTop: '0.5rem' }}>
          <p style={{ color: 'green', fontWeight: 'bold' }}>Temporada encerrada!</p>
          <Link
            to={`/season-summary?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`}
          >
            <button className="cec-btn">Ver resumo da temporada</button>
          </Link>
        </div>
      )}

      {result && (
        <div style={{ marginTop: '1rem' }}>
          <h3>Resultados da Rodada {result.round}</h3>
          <ul>
            {result.results.map(r => (
              <li key={r.fixtureId}>
                {r.homeClubName} {r.homeGoals} — {r.awayGoals} {r.awayClubName}
              </li>
            ))}
          </ul>
          {result.bookings.length > 0 && (
            <div style={{ marginTop: '0.75rem' }}>
              <h4>Cartões</h4>
              <ul>
                {result.bookings.map((b, i) => (
                  <li key={`${b.playerId}-${i}`}>
                    {b.playerName} — {b.type === 'RED_CARD' ? 'Vermelho' : 'Amarelo'}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {result.newSuspensions.length > 0 && (
            <div style={{ marginTop: '0.75rem' }}>
              <h4>Suspensões</h4>
              <ul>
                {result.newSuspensions.map((s, i) => (
                  <li key={`${s.playerId}-${i}`}>
                    {s.playerName} — {s.matches} jogo(s)
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
