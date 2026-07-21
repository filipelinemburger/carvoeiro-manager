import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { IconButton, Tooltip } from '@mui/material'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import { getWatchlist, addToWatchlist, removeFromWatchlist } from '../../api/watchlist'
import { useAuth } from '../../auth/AuthContext'
import type { WatchlistPlayer } from '../../api/types'

type Props = {
  saveGameId: string
  playerId: string
  size?: 'small' | 'medium'
}

/**
 * The Observados star: toggles one rival player on/off the manager's watchlist. Owns the
 * `['watchlist', saveGameId]` query itself so every surface that renders this component (the
 * PlayerDetails watch action today, more later) shares the exact same cache entry as the
 * Observados tab (WatchlistPanel) — starring here updates that list, and vice versa.
 *
 * Renders nothing while the watchlist is still loading (no flash of the wrong state), and
 * flips optimistically on click — with rollback if the request fails — mirroring the
 * withdraw-offer optimistic pattern used elsewhere in the transfer market.
 */
export function WatchStar({ saveGameId, playerId, size = 'small' }: Props) {
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const queryKey = ['watchlist', saveGameId]
  const enabled = Boolean(saveGameId && token)

  const watchlistQuery = useQuery({
    queryKey,
    queryFn: () => getWatchlist(saveGameId, token ?? ''),
    enabled,
    staleTime: Infinity,
  })

  const invalidateWatchlist = () => void queryClient.invalidateQueries({ queryKey })

  const addMut = useMutation({
    mutationFn: () => addToWatchlist(saveGameId, playerId, token ?? ''),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData<WatchlistPlayer[]>(queryKey)
      queryClient.setQueryData<WatchlistPlayer[]>(queryKey, (old) => {
        if ((old ?? []).some(p => p.playerId === playerId)) return old
        // Placeholder row: only `playerId` is used by this component's own derivation. The
        // onSettled invalidation below replaces it with the real row (name, club, etc.) once
        // the request completes — this optimistic entry is transient.
        return [...(old ?? []), { playerId } as unknown as WatchlistPlayer]
      })
      return { previous }
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous)
    },
    onSettled: invalidateWatchlist,
  })

  const removeMut = useMutation({
    mutationFn: () => removeFromWatchlist(saveGameId, playerId, token ?? ''),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData<WatchlistPlayer[]>(queryKey)
      queryClient.setQueryData<WatchlistPlayer[]>(queryKey, (old) =>
        (old ?? []).filter(p => p.playerId !== playerId))
      return { previous }
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous)
    },
    onSettled: invalidateWatchlist,
  })

  if (watchlistQuery.isLoading) return null

  const isWatched = (watchlistQuery.data ?? []).some(p => p.playerId === playerId)
  const pending = addMut.isPending || removeMut.isPending
  const label = isWatched ? 'Remover da observação' : 'Observar jogador'

  return (
    <Tooltip title={label}>
      <span>
        <IconButton
          size={size}
          aria-label={label}
          disabled={pending}
          onClick={() => (isWatched ? removeMut.mutate() : addMut.mutate())}
        >
          {isWatched
            ? <StarIcon fontSize={size} sx={{ color: 'warning.main' }} />
            : <StarBorderIcon fontSize={size} />}
        </IconButton>
      </span>
    </Tooltip>
  )
}
