import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getStandings, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { SaveGameResponse } from '../../api/types'

export function TablePage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const paramCompetitionId = searchParams.get('competitionId') ?? ''
  const managedClubId = searchParams.get('managedClubId') ?? ''

  // The competition can be switched in the UI; it starts at the one the nav linked to
  // (the latest-played-or-next competition).
  const [selectedCompetitionId, setSelectedCompetitionId] = useState(paramCompetitionId)
  const competitionId = selectedCompetitionId || paramCompetitionId

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const { data, isLoading, error } = useQuery({
    queryKey: ['standings', saveGameId, competitionId],
    queryFn: () => getStandings(saveGameId, competitionId, token ?? ''),
    enabled: Boolean(saveGameId && competitionId && token),
  })

  const competitions = saveGame?.competitions ?? []
  const rows = data ?? []

  return (
    <div style={{ padding: '1.5rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0 }}>Classificação</h2>
        {competitions.length > 1 && (
          <label style={{ fontSize: '0.9rem' }}>
            Campeonato:{' '}
            <select
              aria-label="Campeonato"
              value={competitionId}
              onChange={e => setSelectedCompetitionId(e.target.value)}
            >
              {competitions.map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </label>
        )}
      </div>

      {isLoading && <p>Carregando classificação…</p>}
      {error && <p>Erro ao carregar classificação.</p>}

      {!isLoading && !error && (
        <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '1rem' }}>
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
      )}
    </div>
  )
}
