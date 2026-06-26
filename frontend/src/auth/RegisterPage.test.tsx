import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { RegisterPage } from './RegisterPage'
import { AuthProvider } from './AuthContext'
import { MemoryRouter } from 'react-router-dom'

describe('RegisterPage', () => {
  it('renders username and password fields and a create-account button', () => {
    render(
      <MemoryRouter>
        <AuthProvider>
          <RegisterPage />
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(screen.getByLabelText(/usu[aá]rio/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/senha/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /criar conta/i })).toBeInTheDocument()
  })

  it('offers a link back to login', () => {
    render(
      <MemoryRouter>
        <AuthProvider>
          <RegisterPage />
        </AuthProvider>
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: /entrar/i })).toBeInTheDocument()
  })
})
