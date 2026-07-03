import { useEffect } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QueryClient, QueryClientProvider, useQuery, useMutation } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material/styles'
import { muiTheme } from '../muiTheme'
import { LoadingOverlay } from './LoadingOverlay'

// Keeps a query permanently in flight so useIsFetching stays > 0.
function PendingQuery() {
  useQuery({ queryKey: ['pending'], queryFn: () => new Promise<never>(() => {}), retry: false })
  return null
}

// Fires a never-resolving mutation on mount, optionally with a mutationKey.
function PendingMutation({ mutationKey }: { mutationKey?: string[] }) {
  const m = useMutation({ mutationKey, mutationFn: () => new Promise<never>(() => {}) })
  useEffect(() => { m.mutate(undefined as never) }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return null
}

function renderWith(node: React.ReactNode) {
  const qc = new QueryClient()
  return render(
    <QueryClientProvider client={qc}>
      <ThemeProvider theme={muiTheme}>{node}</ThemeProvider>
    </QueryClientProvider>,
  )
}

describe('LoadingOverlay', () => {
  it('shows a spinner while a query is in flight', async () => {
    renderWith(<><PendingQuery /><LoadingOverlay /></>)
    expect(await screen.findByRole('progressbar')).toBeInTheDocument()
  })

  it('shows no spinner when idle', () => {
    renderWith(<LoadingOverlay />)
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('shows a spinner for a normal mutation', async () => {
    renderWith(<><PendingMutation /><LoadingOverlay /></>)
    expect(await screen.findByRole('progressbar')).toBeInTheDocument()
  })

  it('does NOT show a spinner for an in-game live-match mutation', async () => {
    renderWith(<><PendingMutation mutationKey={['liveMatch']} /><LoadingOverlay /></>)
    // Give the mutation a tick to enter its pending state, then confirm no backdrop appeared.
    await waitFor(() => {})
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })
})
