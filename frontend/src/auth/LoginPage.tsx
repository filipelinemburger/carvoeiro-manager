import { useState } from 'react'
import { useNavigate, Link as RouterLink } from 'react-router-dom'
import { Alert, Button, Container, Link, Paper, Stack, TextField, Typography } from '@mui/material'
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
    <Container maxWidth="xs" sx={{ mt: { xs: 4, sm: 8 } }}>
      <Paper elevation={3} sx={{ p: { xs: 3, sm: 4 }, borderRadius: 2 }}>
        <Typography variant="h4" component="h1" sx={{ mb: 3, fontWeight: 700 }}>
          Carvoeiro Manager
        </Typography>
        <form onSubmit={handleSubmit}>
          <Stack spacing={2}>
            <TextField
              label="Usuário"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              fullWidth
              autoComplete="username"
            />
            <TextField
              label="Senha"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              fullWidth
              autoComplete="current-password"
            />
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" fullWidth size="large">
              Entrar
            </Button>
          </Stack>
        </form>
        <Typography sx={{ mt: 2, textAlign: 'center' }}>
          Não tem conta? <Link component={RouterLink} to="/register">Registrar</Link>
        </Typography>
      </Paper>
    </Container>
  )
}
