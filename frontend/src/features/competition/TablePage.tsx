import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getStandings } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'

export function TablePage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const competitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  if (isLoading) return <p>Carregando classificação…</p>
  if (error) return <p>Erro ao carregar classificação.</p>

  const rows = data ?? []

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Classificação</h2>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'center' }}>Pos</th>
            <th style={{ textAlign: 'left' }}>Clube</th>
            <th style={{ textAlign: 'center' }}>P</th>
            <th style={{ textAlign: 'center' }}>W</th>
            <th style={{ textAlign: 'center' }}>D</th>
            <th style={{ textAlign: 'center' }}>L</th>
            <th style={{ textAlign: 'center' }}>GF</th>
            <th style={{ textAlign: 'center' }}>GA</th>
            <th style={{ textAlign: 'center' }}>GD</th>
            <th style={{ textAlign: 'center' }}>Pts</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const isManaged = row.clubId === managedClubId
            return (
              <tr
                key={row.clubId}
                data-managed={isManaged ? 'true' : undefined}
                style={{
                  background: isManaged ? 'rgba(0, 100, 200, 0.1)' : undefined,
                  fontWeight: isManaged ? 'bold' : undefined,
                }}
              >
                <td style={{ textAlign: 'center' }}>{index + 1}</td>
                <td>{row.clubName}</td>
                <td style={{ textAlign: 'center' }}>{row.played}</td>
                <td style={{ textAlign: 'center' }}>{row.won}</td>
                <td style={{ textAlign: 'center' }}>{row.drawn}</td>
                <td style={{ textAlign: 'center' }}>{row.lost}</td>
                <td style={{ textAlign: 'center' }}>{row.goalsFor}</td>
                <td style={{ textAlign: 'center' }}>{row.goalsAgainst}</td>
                <td style={{ textAlign: 'center' }}>{row.goalDifference}</td>
                <td style={{ textAlign: 'center' }}>{row.points}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
