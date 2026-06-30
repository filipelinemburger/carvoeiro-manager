import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getNews, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { AdvanceEventCard } from '../match/AdvanceEventCard'
import type { AdvanceResponse, SaveGameResponse } from '../../api/types'

/**
 * Persisted news feed. Every season advance (match round, transfer window, or
 * season end) is stored server-side and shown here, most recent first, using the
 * same timeline rendering as the live advance result.
 */
export function NewsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const { data: news, isLoading, error } = useQuery<AdvanceResponse[]>({
    queryKey: ['news', saveGameId],
    queryFn: () => getNews(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  if (isLoading) return <p>Carregando notícias…</p>
  if (error) return <p>Erro ao carregar notícias.</p>

  const competitionId = saveGame?.competitionId ?? ''
  const managedClubId = saveGame?.managedClubId ?? ''
  const items = news ?? []

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>Notícias</h2>

      {items.length === 0 ? (
        <p style={{ color: '#555' }}>
          Nenhuma notícia ainda. Clique em <strong>Avançar</strong> para jogar a próxima rodada.
        </p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {items.map((item, i) => (
            <AdvanceEventCard
              // Feed is newest-first and server-ordered; a composite of the event's
              // identifying fields plus the index gives stable, unique keys.
              key={`${item.type}-${item.date ?? 'na'}-${item.round ?? 'na'}-${i}`}
              result={item}
              saveGameId={saveGameId}
              competitionId={competitionId}
              managedClubId={managedClubId}
            />
          ))}
        </div>
      )}
    </div>
  )
}
