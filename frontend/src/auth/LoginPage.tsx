import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from './AuthContext'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await login({ username, password })
      void navigate('/dashboard')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao fazer login')
    }
  }

  return (
    <div style={{ maxWidth: 400, margin: '4rem auto', padding: '2rem', background: 'var(--cec-white)', borderRadius: 8 }}>
      <h1 style={{ color: 'var(--cec-black)', marginTop: 0 }}>
        Elifoot Carvoeiro
      </h1>
      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="username" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            Usuário
          </label>
          <input
            id="username"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            style={{ width: '100%', padding: '0.5rem', boxSizing: 'border-box', borderRadius: 4, border: '1px solid #ccc' }}
          />
        </div>
        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="password" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            Senha
          </label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            style={{ width: '100%', padding: '0.5rem', boxSizing: 'border-box', borderRadius: 4, border: '1px solid #ccc' }}
          />
        </div>
        {error && (
          <p role="alert" style={{ color: 'red', marginBottom: '1rem' }}>
            {error}
          </p>
        )}
        <button type="submit" className="cec-btn" style={{ width: '100%' }}>
          Entrar
        </button>
      </form>
      <p style={{ marginTop: '1rem', textAlign: 'center' }}>
        Não tem conta? <Link to="/register">Registrar</Link>
      </p>
    </div>
  )
}
