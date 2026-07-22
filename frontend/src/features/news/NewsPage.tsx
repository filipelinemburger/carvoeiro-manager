import { useEffect, useRef, useState } from 'react'
import { useQuery, useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Box, Button, List, ListItemButton, ListItemIcon, ListItemText, Stack, Typography } from '@mui/material'
import { getSaveGame } from '../../api/career'
import { getNews, markNewsRead, markAllNewsRead } from '../../api/news'
import { useAuth } from '../../auth/AuthContext'
import { AdvanceEventCard } from '../match/AdvanceEventCard'
import { formatDatePtBR } from '../../utils/date'
import { newsTitle, newsIcon } from './newsMeta'
import type { NewsFeedItem, PageResponse, SaveGameResponse } from '../../api/types'

const NEWS_PAGE_SIZE = 10

/**
 * CM-style news inbox: a compact headline list on top, the selected item's full
 * AdvanceEventCard below. The newest item is auto-selected (and marked read) on landing,
 * so the just-played result shows immediately after Avançar. Read state is optimistic.
 */
export function NewsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const queryClient = useQueryClient()

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })

  const {
    data, isLoading, error, fetchNextPage, hasNextPage, isFetchingNextPage,
  } = useInfiniteQuery<PageResponse<NewsFeedItem>>({
    queryKey: ['news', saveGameId],
    queryFn: ({ pageParam }) => getNews(saveGameId, pageParam as number, NEWS_PAGE_SIZE, token ?? ''),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => (lastPage.page < lastPage.totalPages ? lastPage.page + 1 : undefined),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const markReadMutation = useMutation({
    mutationFn: (newsItemId: string) => markNewsRead(saveGameId, newsItemId, token ?? ''),
    onMutate: async (newsItemId: string) => {
      await queryClient.cancelQueries({ queryKey: ['news', saveGameId] })
      const prevNews = queryClient.getQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId])
      const prevCount = queryClient.getQueryData<number>(['newsUnreadCount', saveGameId])
      let wasUnread = false
      queryClient.setQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId], (old) => {
        if (!old) return old
        return {
          ...old,
          pages: old.pages.map(pg => ({
            ...pg,
            content: pg.content.map(it => {
              if (it.id === newsItemId && !it.read) { wasUnread = true; return { ...it, read: true } }
              return it
            }),
          })),
        }
      })
      if (wasUnread && typeof prevCount === 'number') {
        queryClient.setQueryData<number>(['newsUnreadCount', saveGameId], Math.max(0, prevCount - 1))
      }
      return { prevNews, prevCount }
    },
    onError: (_e, _id, ctx) => {
      if (ctx?.prevNews) queryClient.setQueryData(['news', saveGameId], ctx.prevNews)
      if (ctx?.prevCount !== undefined) queryClient.setQueryData(['newsUnreadCount', saveGameId], ctx.prevCount)
    },
    onSettled: () => { void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] }) },
  })

  const markAllReadMutation = useMutation({
    mutationFn: () => markAllNewsRead(saveGameId, token ?? ''),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: ['news', saveGameId] })
      const prevNews = queryClient.getQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId])
      const prevCount = queryClient.getQueryData<number>(['newsUnreadCount', saveGameId])
      queryClient.setQueryData<InfiniteData<PageResponse<NewsFeedItem>>>(['news', saveGameId], (old) => {
        if (!old) return old
        return {
          ...old,
          pages: old.pages.map(pg => ({ ...pg, content: pg.content.map(it => (it.read ? it : { ...it, read: true })) })),
        }
      })
      queryClient.setQueryData<number>(['newsUnreadCount', saveGameId], 0)
      return { prevNews, prevCount }
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prevNews) queryClient.setQueryData(['news', saveGameId], ctx.prevNews)
      if (ctx?.prevCount !== undefined) queryClient.setQueryData(['newsUnreadCount', saveGameId], ctx.prevCount)
    },
    onSettled: () => { void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] }) },
  })

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const lastNewestId = useRef<string | null>(null)
  const items = (data?.pages ?? []).flatMap(p => p.content)

  // Auto-select the newest item on landing and whenever a strictly-newer item arrives
  // (e.g. after Avançar prepends a fresh result), or re-select when the current selection
  // drops out of the list. Marks the newly-selected item read if it was unread.
  useEffect(() => {
    if (items.length === 0) {
      lastNewestId.current = null
      if (selectedId !== null) setSelectedId(null)
      return
    }
    const newest = items[0]
    const newestArrived = newest.id !== lastNewestId.current
    const selectionPresent = selectedId !== null && items.some(it => it.id === selectedId)
    if (newestArrived || !selectionPresent) {
      lastNewestId.current = newest.id
      setSelectedId(newest.id)
      if (!newest.read) markReadMutation.mutate(newest.id)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, items[0]?.id, selectedId])

  if (isLoading) return <Typography>Carregando notícias…</Typography>
  if (error) return <Typography color="error">Erro ao carregar notícias.</Typography>

  const competitionId = saveGame?.competitionId ?? ''
  const managedClubId = saveGame?.managedClubId ?? ''
  const selected = items.find(it => it.id === selectedId) ?? items[0] ?? null
  const loadedUnread = items.filter(it => !it.read).length

  const handleSelect = (item: NewsFeedItem) => {
    setSelectedId(item.id)
    if (!item.read) markReadMutation.mutate(item.id)
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5" component="h2" sx={{ fontWeight: 700, m: 0 }}>
          Notícias
        </Typography>
        <Button
          size="small"
          variant="outlined"
          disabled={loadedUnread === 0 || markAllReadMutation.isPending}
          onClick={() => markAllReadMutation.mutate()}
        >
          Marcar todas como lidas
        </Button>
      </Stack>

      {items.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Nenhuma notícia ainda. Clique em <Box component="strong">Avançar</Box> para jogar a próxima rodada.
        </Typography>
      ) : (
        <>
          <List
            sx={{
              border: '1px solid rgba(255,255,255,0.12)',
              borderRadius: 2,
              p: 0,
              mb: 2,
              // Keep the inbox compact: show ~3 rows and scroll the rest so it doesn't eat the screen.
              maxHeight: 216,
              overflowY: 'auto',
            }}
          >
            {items.map(item => {
              const isSelected = item.id === selected?.id
              return (
                <ListItemButton
                  key={item.id}
                  selected={isSelected}
                  onClick={() => handleSelect(item)}
                  sx={{
                    borderLeft: isSelected ? '3px solid var(--cec-yellow)' : '3px solid transparent',
                    opacity: item.read ? 0.7 : 1,
                  }}
                >
                  <ListItemIcon sx={{ minWidth: 36, color: 'inherit' }}>
                    {newsIcon(item.payload.type)}
                  </ListItemIcon>
                  <ListItemText
                    primary={
                      <Typography component="span" sx={{ fontWeight: item.read ? 400 : 700 }}>
                        {newsTitle(item.payload)}
                      </Typography>
                    }
                    secondary={formatDatePtBR(item.payload.date)}
                  />
                  {!item.read && (
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'var(--cec-yellow)', ml: 1 }} />
                  )}
                </ListItemButton>
              )
            })}
          </List>

          {hasNextPage && (
            <Box sx={{ display: 'flex', justifyContent: 'center', mb: 2 }}>
              <Button variant="text" disabled={isFetchingNextPage} onClick={() => fetchNextPage()}>
                {isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
              </Button>
            </Box>
          )}

          {selected && (
            <AdvanceEventCard
              result={selected.payload}
              saveGameId={saveGameId}
              competitionId={competitionId}
              managedClubId={managedClubId}
            />
          )}
        </>
      )}
    </Box>
  )
}
