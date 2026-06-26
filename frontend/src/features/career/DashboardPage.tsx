import { useAuth } from '../../auth/AuthContext'
import { useNavigate } from 'react-router-dom'

export function DashboardPage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    void navigate('/login')
  }

  return (
    <div>
      <header className="cec-header">
        <h1 style={{ margin: 0, fontSize: '1.25rem' }}>Elifoot Carvoeiro</h1>
      </header>
      <main style={{ padding: '2rem' }}>
        <h2>Bem-vindo, {user?.username}</h2>
        <button className="cec-btn" onClick={handleLogout}>
          Sair
        </button>
      </main>
    </div>
  )
}
