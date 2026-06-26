import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { LoginPage } from './LoginPage'
import { AuthProvider } from './AuthContext'
import { MemoryRouter } from 'react-router-dom'

describe('LoginPage', () => {
  it('renders username and password fields and a submit button', () => {
    render(
      <MemoryRouter>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(screen.getByLabelText(/usu[aá]rio/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/senha/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /entrar/i })).toBeInTheDocument()
  })
})
