import { useState, useRef, useEffect, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthContext'
import { advanceRound, getSaveGame } from '../api/career'
import type { AdvanceResponse, SaveGameResponse } from '../api/types'

interface LayoutProps {
  children: ReactNode
}

const saveGameKeyFor = (userId: string | undefined) =>
  `elifoot_save_game_id_${userId ?? 'anon'}`

export function Layout({ children }: LayoutProps) {
  const { user, token, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // Read saveGameId from search params first, then localStorage fallback
  const [searchParams] = useSearchParams()
  const paramSaveGameId = searchParams.get('saveGameId')
  const localSaveGameId = localStorage.getItem(saveGameKeyFor(user?.userId))
  const saveGameId = paramSaveGameId ?? localSaveGameId

  const { data: saveGame } = useQuery<SaveGameResponse>({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 30_000,
  })

  const [gearOpen, setGearOpen] = useState(false)
  const gearRef = useRef<HTMLDivElement>(null)

  // Close gear dropdown on outside click
  useEffect(() => {
    if (!gearOpen) return
    const handler = (e: MouseEvent) => {
      if (gearRef.current && !gearRef.current.contains(e.target as Node)) {
        setGearOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [gearOpen])

  const advanceMutation = useMutation<AdvanceResponse>({
    mutationFn: () => advanceRound(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      void queryClient.invalidateQueries({ queryKey: ['finances'] })
      void queryClient.invalidateQueries({ queryKey: ['offers'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      // The advance outcome is persisted as a news item; show it on the News tab.
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })

  const isDashboard = location.pathname === '/dashboard'
  // Use the persisted save status too, so Avançar stays disabled after a page
  // reload following a season-ending advance (the mutation result is ephemeral).
  const seasonFinished =
    advanceMutation.data?.seasonFinished === true || saveGame?.status === 'FINISHED'

  const handleLogout = () => {
    setGearOpen(false)
    logout()
    void navigate('/login')
  }

  // Build navigation links (only when save game is available)
  const managedClubId = saveGame?.managedClubId
  const competitionId = saveGame?.competitionId
  const sgId = saveGame?.id ?? saveGameId

  const hasSaveGame = Boolean(sgId && managedClubId && competitionId)

  return (
    <>
      <header className="cec-header">
        {/* Left: back arrow */}
        <div style={{ minWidth: '36px' }}>
          {!isDashboard && (
            <button
              className="cec-icon-btn"
              onClick={() => navigate(-1)}
              aria-label="Voltar"
              title="Voltar"
            >
              ←
            </button>
          )}
        </div>

        {/* Middle: title + nav */}
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: '0.25rem', overflow: 'hidden' }}>
          <span style={{ fontWeight: 700, fontSize: '1rem', marginRight: '0.5rem', whiteSpace: 'nowrap' }}>
            Elifoot Carvoeiro
          </span>

          <nav style={{ display: 'flex', alignItems: 'center', gap: '0.1rem', flexWrap: 'nowrap', overflow: 'hidden' }}>
            <NavLink
              to="/dashboard"
              className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
            >
              Início
            </NavLink>

            {hasSaveGame && (
              <>
                <NavLink
                  to={`/squad?saveGameId=${sgId}&clubId=${managedClubId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Escalação
                </NavLink>

                <NavLink
                  to={`/table?saveGameId=${sgId}&competitionId=${competitionId}&managedClubId=${managedClubId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Classificação
                </NavLink>

                <NavLink
                  to={`/finances?saveGameId=${sgId}&clubId=${managedClubId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Finanças
                </NavLink>

                <NavLink
                  to={`/transfers?saveGameId=${sgId}&clubId=${managedClubId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Mercado
                </NavLink>

                <NavLink
                  to={`/free-agents?saveGameId=${sgId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Livres
                </NavLink>

                <NavLink
                  to={`/history?saveGameId=${sgId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  Histórico
                </NavLink>

                <NavLink
                  to={`/news?saveGameId=${sgId}`}
                  className={({ isActive }) => `cec-nav-link${isActive ? ' active' : ''}`}
                >
                  News
                </NavLink>

                <button
                  className="cec-btn-primary"
                  disabled={advanceMutation.isPending || seasonFinished || !sgId}
                  onClick={() => advanceMutation.mutate()}
                  style={{ marginLeft: '0.25rem' }}
                >
                  {advanceMutation.isPending ? 'Avançando…' : 'Avançar'}
                </button>
              </>
            )}
          </nav>
        </div>

        {/* Right: gear */}
        <div ref={gearRef} style={{ position: 'relative', minWidth: '36px' }}>
          <button
            className="cec-icon-btn"
            onClick={() => setGearOpen(o => !o)}
            aria-label="Configurações"
            aria-expanded={gearOpen}
          >
            ⚙️
          </button>

          {gearOpen && (
            <div
              style={{
                position: 'absolute',
                right: 0,
                top: '110%',
                background: '#fff',
                border: '1px solid #ddd',
                borderRadius: '6px',
                boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
                minWidth: '120px',
                zIndex: 200,
              }}
            >
              <button
                onClick={handleLogout}
                style={{
                  display: 'block',
                  width: '100%',
                  padding: '0.6rem 1rem',
                  background: 'none',
                  border: 0,
                  cursor: 'pointer',
                  textAlign: 'left',
                  fontSize: '0.9rem',
                  color: '#111',
                }}
              >
                Sair
              </button>
            </div>
          )}
        </div>
      </header>

      <main style={{ flex: 1 }}>
        <div className="cec-content-panel">
          {children}
        </div>
      </main>

      {/* Advance error toast */}
      {advanceMutation.isError && (
        <div
          role="alert"
          style={{
            position: 'fixed',
            bottom: '1rem',
            left: '50%',
            transform: 'translateX(-50%)',
            background: '#c00',
            color: '#fff',
            padding: '0.6rem 1.25rem',
            borderRadius: '6px',
            zIndex: 500,
          }}
        >
          Erro ao avançar.
        </div>
      )}
    </>
  )
}
