import { Backdrop, CircularProgress } from '@mui/material'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'

/**
 * A global, full-screen backdrop with a spinner shown whenever the app is
 * loading: any query that is fetching without data yet (initial tab/page loads)
 * or any mutation in flight (Avançar, saving a line-up, offers, renewals…).
 *
 * Queries with `data === undefined` are the ones genuinely "loading content";
 * background refetches (which already have data) are intentionally ignored so
 * the overlay doesn't flash on window-focus/stale refetches.
 *
 * The backdrop sits above everything (including dialogs) and captures pointer
 * events, so the user cannot interact with the app while it is loading.
 */
export function LoadingOverlay() {
  const loading = useIsFetching({ predicate: query => query.state.data === undefined })
  const mutating = useIsMutating()
  const busy = loading + mutating > 0

  return (
    <Backdrop open={busy} sx={{ color: '#fff', zIndex: theme => theme.zIndex.modal + 1 }}>
      <CircularProgress color="inherit" aria-label="Carregando" />
    </Backdrop>
  )
}
