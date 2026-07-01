import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { RegisterPage } from './RegisterPage'
import { AuthProvider } from './AuthContext'
import { MemoryRouter } from 'react-router-dom'
import { apiPost } from '../api/client'

vi.mock('../api/client', () => ({
  apiPost: vi.fn(),
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <RegisterPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('RegisterPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('renders username and password fields and a create-account button', () => {
    renderPage()
    expect(screen.getByLabelText(/usu[aá]rio/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/senha/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /criar conta/i })).toBeInTheDocument()
  })

  it('offers a link back to login', () => {
    renderPage()
    expect(screen.getByRole('link', { name: /entrar/i })).toBeInTheDocument()
  })

  it('sends only one registration request when submitted repeatedly while in flight', () => {
    // Given a registration request that stays pending (simulating a slow/cold backend)
    ;(apiPost as Mock).mockReturnValue(new Promise(() => {}))
    renderPage()
    fireEvent.change(screen.getByLabelText(/usu[aá]rio/i), { target: { value: 'joga10' } })
    fireEvent.change(screen.getByLabelText(/senha/i), { target: { value: 'password123' } })

    const button = screen.getByRole('button', { name: /criar conta/i })

    // When the user clicks "Criar conta" several times in quick succession
    fireEvent.click(button)
    fireEvent.click(button)
    fireEvent.click(button)

    // Then exactly one request is sent and the button is disabled with feedback
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/auth/register', { username: 'joga10', password: 'password123' })
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent(/criando/i)
  })
})
