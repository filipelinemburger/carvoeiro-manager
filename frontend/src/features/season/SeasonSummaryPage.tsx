import { useQuery, useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { getStandings, getTopScorers, rolloverSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { RolloverResponse } from '../../api/types'

export function SeasonSummaryPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const competitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  const standings = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const scorers = useQuery({
    queryKey: ['topScorers', saveGameId, competitionId],
    queryFn: () => getTopScorers(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const rollover = useMutation<RolloverResponse>({
    mutationFn: () => rolloverSeason(saveGameId, token ?? ''),
  })

  if (standings.isLoading) return <p>Carregando resumo da temporada…</p>
  if (standings.error) return <p>Erro ao carregar o resumo da temporada.</p>

  const table = standings.data ?? []
  const champion = table[0]
  const result = rollover.data

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Fim de Temporada</h2>
      {champion && (
        <p style={{ fontWeight: 'bold', color: 'green' }}>Campeão: {champion.clubName} 🏆</p>
      )}

      <h3>Classificação Final</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'center' }}>Pos</th>
            <th style={{ textAlign: 'left' }}>Clube</th>
            <th style={{ textAlign: 'center' }}>P</th>
            <th style={{ textAlign: 'center' }}>GD</th>
            <th style={{ textAlign: 'center' }}>Pts</th>
          </tr>
        </thead>
        <tbody>
          {table.map((row, index) => {
            const isManaged = row.clubId === managedClubId
            return (
              <tr key={row.clubId} data-managed={isManaged ? 'true' : undefined}
                  style={{ fontWeight: isManaged ? 'bold' : undefined }}>
                <td style={{ textAlign: 'center' }}>{index + 1}</td>
                <td>{row.clubName}</td>
                <td style={{ textAlign: 'center' }}>{row.played}</td>
                <td style={{ textAlign: 'center' }}>{row.goalDifference}</td>
                <td style={{ textAlign: 'center' }}>{row.points}</td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <h3>Artilheiros</h3>
      <ul>
        {(scorers.data ?? []).map((s, i) => (
          <li key={`${s.playerName}-${i}`}>{s.playerName} ({s.clubName}) — {s.goals}</li>
        ))}
      </ul>

      {!result && (
        <button
          className="cec-btn"
          disabled={rollover.isPending}
          onClick={() => rollover.mutate()}
        >
          {rollover.isPending ? 'Processando…' : 'Iniciar próxima temporada'}
        </button>
      )}
      {rollover.isError && <p style={{ color: 'red' }}>Erro ao iniciar a próxima temporada.</p>}

      {result && (
        <div style={{ marginTop: '1rem' }}>
          <p style={{ color: 'green' }}>Temporada {result.seasonYear} iniciada!</p>
          {result.retirements.length > 0 && (
            <div>
              <h4>Aposentadorias</h4>
              <ul>
                {result.retirements.map((c, i) => (
                  <li key={`ret-${i}`}>{c.playerName} ({c.position}, {c.age} anos)</li>
                ))}
              </ul>
            </div>
          )}
          {result.newcomers.length > 0 && (
            <div>
              <h4>Novatos</h4>
              <ul>
                {result.newcomers.map((c, i) => (
                  <li key={`new-${i}`}>{c.playerName} ({c.position}, {c.age} anos)</li>
                ))}
              </ul>
            </div>
          )}
          <Link to="/dashboard"><button className="cec-btn">Voltar ao painel</button></Link>
        </div>
      )}
    </div>
  )
}
