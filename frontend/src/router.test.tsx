import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppRouter } from './router'
import { AuthProvider } from './auth/AuthContext'

describe('routing', () => {
  it('navigates from login to the register form when "Registrar" is clicked', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </MemoryRouter>,
    )

    // On the login page
    expect(screen.getByRole('button', { name: /entrar/i })).toBeInTheDocument()

    // Click the "Registrar" link
    await user.click(screen.getByRole('link', { name: /registrar/i }))

    // The register form is now shown (regression test for the dead /register link)
    expect(screen.getByRole('button', { name: /criar conta/i })).toBeInTheDocument()
  })

  it('redirects "/" to /login when logged out', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: /entrar/i })).toBeInTheDocument()
  })

  it('redirects "/" to /dashboard when logged in', () => {
    localStorage.setItem('carvoeiro_manager_token', 'test-token')
    localStorage.setItem('carvoeiro_manager_user', JSON.stringify({ username: 'u', userId: 'u1' }))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/']}>
          <AuthProvider>
            <AppRouter />
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByText(/bem-vindo/i)).toBeInTheDocument()
  })
})
