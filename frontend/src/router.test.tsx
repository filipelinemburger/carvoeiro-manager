import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
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
})
