import { useQuery, useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Box, Button, Stack, Typography } from '@mui/material'
import { getNews, getSaveGame } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { AdvanceEventCard } from '../match/AdvanceEventCard'
import type { AdvanceResponse, PageResponse, SaveGameResponse } from '../../api/types'

const NEWS_PAGE_SIZE = 10

/**
 * Persisted news feed. Every season advance (match round, transfer window, or
 * season end) is stored server-side and shown here, most recent first, using the
 * same timeline rendering as the live advance result. Loaded 10 at a time via
 * "Carregar mais" — only the loaded pages live in the client cache.
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

  const {
    data, isLoading, error, fetchNextPage, hasNextPage, isFetchingNextPage,
  } = useInfiniteQuery<PageResponse<AdvanceResponse>>({
    queryKey: ['news', saveGameId],
    queryFn: ({ pageParam }) => getNews(saveGameId, pageParam as number, NEWS_PAGE_SIZE, token ?? ''),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => (lastPage.page < lastPage.totalPages ? lastPage.page + 1 : undefined),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  if (isLoading) return <Typography>Carregando notícias…</Typography>
  if (error) return <Typography color="error">Erro ao carregar notícias.</Typography>

  const competitionId = saveGame?.competitionId ?? ''
  const managedClubId = saveGame?.managedClubId ?? ''
  const items = (data?.pages ?? []).flatMap(p => p.content)

  return (
    <Box>
      <Typography variant="h5" component="h2" sx={{ fontWeight: 700, mt: 0, mb: 2 }}>
        Notícias
      </Typography>

      {items.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Nenhuma notícia ainda. Clique em <Box component="strong">Avançar</Box> para jogar a próxima rodada.
        </Typography>
      ) : (
        <Stack spacing={2}>
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
        </Stack>
      )}

      {hasNextPage && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 2 }}>
          <Button variant="outlined" disabled={isFetchingNextPage} onClick={() => fetchNextPage()}>
            {isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
          </Button>
        </Box>
      )}
    </Box>
  )
}
