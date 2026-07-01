import { useRef, useState } from 'react'
import { useNavigate, Link as RouterLink } from 'react-router-dom'
import { Alert, Button, Container, Link, Paper, Stack, TextField, Typography } from '@mui/material'
import { useAuth } from './AuthContext'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // Synchronous re-entry guard: React state updates are async, so a rapid second
  // click or Enter press could fire before `submitting` disables the button.
  const inFlight = useRef(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (inFlight.current) return
    inFlight.current = true
    setSubmitting(true)
    setError(null)
    try {
      await register({ username, password })
      void navigate('/dashboard')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao criar conta')
      // Re-enable only on failure; on success we navigate away and unmount.
      inFlight.current = false
      setSubmitting(false)
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
              autoComplete="new-password"
              slotProps={{ htmlInput: { minLength: 8 } }}
            />
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" fullWidth size="large" disabled={submitting}>
              {submitting ? 'Criando conta…' : 'Criar conta'}
            </Button>
          </Stack>
        </form>
        <Typography sx={{ mt: 2, textAlign: 'center' }}>
          Já tem conta? <Link component={RouterLink} to="/login">Entrar</Link>
        </Typography>
      </Paper>
    </Container>
  )
}
