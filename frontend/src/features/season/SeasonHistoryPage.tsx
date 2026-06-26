import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSeasonHistory, getSeason } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'

export function SeasonHistoryPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [selectedYear, setSelectedYear] = useState<number | null>(null)

  const history = useQuery({
    queryKey: ['seasonHistory', saveGameId],
    queryFn: () => getSeasonHistory(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  const detail = useQuery({
    queryKey: ['season', saveGameId, selectedYear],
    queryFn: () => getSeason(saveGameId, selectedYear!, token ?? ''),
    enabled: Boolean(saveGameId && token && selectedYear !== null),
  })

  if (history.isLoading) return <p>Carregando histórico…</p>
  if (history.error) return <p>Erro ao carregar o histórico.</p>

  const seasons = history.data ?? []

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Histórico de Temporadas</h2>
      {seasons.length === 0 && <p>Nenhuma temporada concluída ainda.</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Ano</th>
            <th style={{ textAlign: 'left' }}>Campeão</th>
            <th style={{ textAlign: 'center' }}>Sua posição</th>
            <th style={{ textAlign: 'left' }}>Artilheiro</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {seasons.map(s => (
            <tr key={s.seasonYear}>
              <td>{s.seasonYear}</td>
              <td>{s.championClubName}</td>
              <td style={{ textAlign: 'center' }}>{s.managedClubPosition}º</td>
              <td>{s.topScorerName ? `${s.topScorerName} (${s.topScorerGoals})` : '—'}</td>
              <td>
                <button className="cec-btn" onClick={() => setSelectedYear(s.seasonYear)}>
                  Ver {s.seasonYear}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selectedYear !== null && detail.isLoading && <p>Carregando temporada {selectedYear}…</p>}
      {detail.data && (
        <div>
          <h3>{detail.data.competitionName} {detail.data.seasonYear}</h3>
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
              {detail.data.table.map(row => (
                <tr key={row.position}>
                  <td style={{ textAlign: 'center' }}>{row.position}</td>
                  <td>{row.clubName}</td>
                  <td style={{ textAlign: 'center' }}>{row.played}</td>
                  <td style={{ textAlign: 'center' }}>{row.goalDifference}</td>
                  <td style={{ textAlign: 'center' }}>{row.points}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h4>Artilheiros</h4>
          <ul>
            {detail.data.topScorers.map((s, i) => (
              <li key={`${s.playerName}-${i}`}>{s.playerName} ({s.clubName}) — {s.goals}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
