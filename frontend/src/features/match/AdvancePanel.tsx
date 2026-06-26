import { useMutation, useQueryClient } from '@tanstack/react-query'
import { advanceRound } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { SimulateRoundResponse } from '../../api/types'

interface AdvancePanelProps {
  saveGameId: string
  competitionId: string
}

export function AdvancePanel({ saveGameId, competitionId }: AdvancePanelProps) {
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
        <p style={{ color: 'green', fontWeight: 'bold' }}>Temporada encerrada!</p>
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
        </div>
      )}
    </div>
  )
}
