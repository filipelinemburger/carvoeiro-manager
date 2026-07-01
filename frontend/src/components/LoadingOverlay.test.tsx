import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material/styles'
import { muiTheme } from '../muiTheme'
import { LoadingOverlay } from './LoadingOverlay'

// Keeps a query permanently in flight so useIsFetching stays > 0.
function PendingQuery() {
  useQuery({ queryKey: ['pending'], queryFn: () => new Promise<never>(() => {}), retry: false })
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
})
