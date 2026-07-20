import { useState, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AppBar, Toolbar, Box, Button, IconButton, Typography, Menu, MenuItem,
  Drawer, List, ListItemButton, ListItemText, Alert, Snackbar, Container, Paper, Divider,
} from '@mui/material'
import MenuIcon from '@mui/icons-material/Menu'
import SettingsIcon from '@mui/icons-material/Settings'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { useAuth } from '../auth/AuthContext'
import { advanceRound, getAdvancePreview, getLineup, getSaveGame } from '../api/career'
import { deleteAccount } from '../api/account'
import { getLiveMatch, kickoffLiveMatch } from '../api/liveMatch'
import { saveGameKeyFor } from '../features/career/saveGameKey'
import { LoadCareerDialog } from '../features/career/LoadCareerDialog'
import { NewCareerDialog } from '../features/career/NewCareerDialog'
import type { AdvancePreviewResponse, AdvanceResponse, LiveMatchState, SaveGameResponse } from '../api/types'

interface LayoutProps {
  children: ReactNode
}

/** Brand colors: black bar, yellow text (Criciúma EC identity). */
const BAR_BG = '#111111'
const BAR_FG = '#f7d417'

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

  // Look-ahead at the next advance: drives whether "Avançar" is disabled.
  const { data: preview } = useQuery<AdvancePreviewResponse>({
    queryKey: ['advancePreview', saveGameId],
    queryFn: () => getAdvancePreview(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 10_000,
  })

  const { data: liveMatch } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 5_000,
  })

  // Whether the managed club already has a saved line-up. Before the first game the
  // user must pick one (getLineup resolves to null when none has been saved).
  const managedClubIdForLineup = saveGame?.managedClubId
  const { data: managedLineup, isSuccess: lineupLoaded } = useQuery({
    queryKey: ['lineup', saveGameId, managedClubIdForLineup],
    queryFn: () => getLineup(saveGameId!, managedClubIdForLineup!, token ?? ''),
    enabled: Boolean(saveGameId && managedClubIdForLineup && token),
    retry: false,
    staleTime: 10_000,
  })

  const [gearAnchor, setGearAnchor] = useState<null | HTMLElement>(null)
  const gearOpen = Boolean(gearAnchor)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [loadCareerOpen, setLoadCareerOpen] = useState(false)
  const [newCareerOpen, setNewCareerOpen] = useState(false)

  const advanceMutation = useMutation<AdvanceResponse>({
    mutationFn: () => advanceRound(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      void queryClient.invalidateQueries({ queryKey: ['finances'] })
      void queryClient.invalidateQueries({ queryKey: ['offers'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      void queryClient.invalidateQueries({ queryKey: ['market'] })
      void queryClient.invalidateQueries({ queryKey: ['clubs'] })
      void queryClient.invalidateQueries({ queryKey: ['staffCandidates'] })
      // Advancing processes TRANSFER days — renewals/locks/pokes change both pre-contract lists.
      void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
      // The advance outcome is persisted as a news item; show it on the News tab.
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })

  const kickoffMutation = useMutation<LiveMatchState>({
    mutationFn: () => kickoffLiveMatch(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['liveMatch', saveGameId] })
      void navigate(`/match?saveGameId=${saveGameId}`)
    },
  })

  const handleAdvance = () => {
    if (liveMatch) { void navigate(`/match?saveGameId=${saveGameId}`); return }
    if (preview?.managedClubPlaysNext) { kickoffMutation.mutate(); return }
    advanceMutation.mutate()
  }

  const isDashboard = location.pathname === '/dashboard'
  // On the live-match screen the match has its own controls (Pausar/Substituir), so a
  // top-bar "Retomar partida" (which just navigates to /match) is redundant — hide it there.
  const isMatchScreen = location.pathname === '/match'
  // Use the persisted save status too, so Avançar stays disabled after a page
  // reload following a season-ending advance (the mutation result is ephemeral).
  const seasonFinished =
    advanceMutation.data?.seasonFinished === true || saveGame?.status === 'FINISHED'

  const handleLogout = () => {
    setGearAnchor(null)
    logout()
    void navigate('/login')
  }

  // "Nova Carreira" lives in the gear menu: start a fresh career at any time. The current
  // save isn't deleted, but the active pointer moves to the new one — confirm to avoid slips.
  const handleNewCareer = () => {
    setGearAnchor(null)
    const hasActive = Boolean(saveGameId)
    if (hasActive && !window.confirm('Começar uma nova carreira? A carreira atual continuará salva, mas você voltará ao início.')) {
      return
    }
    setNewCareerOpen(true)
  }

  // Permanently deletes the account and every save game/piece of data it owns.
  const deleteAccountMutation = useMutation({
    mutationFn: () => deleteAccount(token ?? ''),
    onSuccess: () => {
      queryClient.clear()
      logout()
      void navigate('/login')
    },
  })

  const handleDeleteAccount = () => {
    setGearAnchor(null)
    if (!window.confirm('Excluir sua conta? Todos os seus dados (carreiras, elenco, finanças e histórico) serão apagados permanentemente. Esta ação não pode ser desfeita.')) {
      return
    }
    deleteAccountMutation.mutate()
  }

  // Build navigation links (only when save game is available)
  const managedClubId = saveGame?.managedClubId
  const competitionId = saveGame?.competitionId
  const sgId = saveGame?.id ?? saveGameId

  const hasSaveGame = Boolean(sgId && managedClubId && competitionId)

  // Classificação defaults to the latest-played (or next) match's competition.
  const standingsCompetitionId = preview?.defaultStandingsCompetitionId ?? competitionId

  // Reasons the advance must be blocked before the click (computed from the preview).
  // No saved line-up before a match the managed club plays: the user must pick a team first.
  const blockNoLineup =
    preview?.managedClubPlaysNext === true && lineupLoaded && !managedLineup
  const blockIneligible =
    preview?.managedClubPlaysNext === true && (preview?.ineligibleStarters.length ?? 0) > 0
  const blockWindow =
    preview?.windowClosingAfterAdvance === true && (preview?.pendingTransfers ?? 0) > 0
  const advanceBlockReason = blockNoLineup
    ? 'Escale seu time antes do primeiro jogo.'
    : blockIneligible
      ? 'Há titular(es) suspenso(s) ou lesionado(s) na escalação. Ajuste o time antes de avançar.'
      : blockWindow
        ? 'A janela de transferências vai fechar e há proposta(s) pendente(s). Resolva-as antes de avançar.'
        : null
  const advanceDisabled =
    advanceMutation.isPending || seasonFinished || !sgId || advanceBlockReason !== null

  // Section links shown once a save game exists — reused by the desktop bar and the
  // mobile Drawer so the two never drift.
  const saveLinks = hasSaveGame
    ? [
        { to: `/squad?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Escalação' },
        { to: `/base?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Base' },
        { to: `/table?saveGameId=${sgId}&competitionId=${standingsCompetitionId}&managedClubId=${managedClubId}`, label: 'Classificação' },
        { to: `/clubs?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Clubes' },
        { to: `/finances?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Finanças' },
        { to: `/staff?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Comissão Técnica' },
        { to: `/transfers?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Mercado' },
        { to: `/history?saveGameId=${sgId}`, label: 'Histórico' },
        { to: `/news?saveGameId=${sgId}`, label: 'News' },
      ]
    : []

  const navLinks = [{ to: '/dashboard', label: 'Início' }, ...saveLinks]

  // Styles a NavLink as an on-bar pill: yellow text, filled yellow when active.
  const barLinkSx = {
    color: BAR_FG,
    textDecoration: 'none',
    px: 1,
    py: 0.5,
    borderRadius: 1,
    fontSize: '0.875rem',
    fontWeight: 500,
    whiteSpace: 'nowrap',
    '&:hover': { bgcolor: 'rgba(255,255,255,0.1)' },
    '&.active': { bgcolor: BAR_FG, color: BAR_BG, fontWeight: 700 },
  } as const

  return (
    <>
      <AppBar position="sticky" elevation={0} sx={{ bgcolor: BAR_BG, color: BAR_FG }}>
        <Toolbar variant="dense" sx={{ gap: 1 }}>
          {/* Left: back arrow (hidden on the dashboard) */}
          {!isDashboard && (
            <IconButton
              edge="start"
              onClick={() => navigate(-1)}
              aria-label="Voltar"
              title="Voltar"
              sx={{ color: BAR_FG }}
            >
              <ArrowBackIcon />
            </IconButton>
          )}

          <Typography component="span" sx={{ fontWeight: 700, mr: 1, whiteSpace: 'nowrap' }}>
            Carvoeiro Manager
          </Typography>

          {/* Desktop: inline nav links */}
          <Box component="nav" sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'center', gap: 0.25 }}>
            {navLinks.map(link => (
              <Box key={link.to} component={NavLink} to={link.to} sx={barLinkSx}>
                {link.label}
              </Box>
            ))}
          </Box>

          <Box sx={{ flexGrow: 1 }} />

          {hasSaveGame && !isMatchScreen && (
            <Button
              color="primary"
              disabled={advanceDisabled || kickoffMutation.isPending}
              title={advanceBlockReason ?? undefined}
              onClick={handleAdvance}
            >
              {liveMatch ? 'Retomar partida'
                : advanceMutation.isPending || kickoffMutation.isPending ? 'Avançando…' : 'Avançar'}
            </Button>
          )}

          {/* Mobile: hamburger opens the Drawer with all sections */}
          {hasSaveGame && (
            <IconButton
              aria-label="Abrir menu"
              onClick={() => setDrawerOpen(true)}
              sx={{ color: BAR_FG, display: { xs: 'inline-flex', md: 'none' } }}
            >
              <MenuIcon />
            </IconButton>
          )}

          <IconButton
            onClick={(e) => setGearAnchor(e.currentTarget)}
            aria-label="Configurações"
            aria-expanded={gearOpen}
            edge="end"
            sx={{ color: BAR_FG }}
          >
            <SettingsIcon />
          </IconButton>

          <Menu anchorEl={gearAnchor} open={gearOpen} onClose={() => setGearAnchor(null)}>
            <MenuItem onClick={handleNewCareer}>Nova Carreira</MenuItem>
            <MenuItem onClick={() => { setLoadCareerOpen(true); setGearAnchor(null) }}>
              Carregar jogo salvo
            </MenuItem>
            <MenuItem onClick={handleLogout}>Sair</MenuItem>
            <Divider />
            <MenuItem
              onClick={handleDeleteAccount}
              disabled={deleteAccountMutation.isPending}
              sx={{ color: 'error.main' }}
            >
              {deleteAccountMutation.isPending ? 'Excluindo…' : 'Excluir conta'}
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>

      <LoadCareerDialog open={loadCareerOpen} onClose={() => setLoadCareerOpen(false)} />
      <NewCareerDialog open={newCareerOpen} onClose={() => setNewCareerOpen(false)} />

      {/* Mobile navigation drawer */}
      <Drawer anchor="right" open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <Box sx={{ width: 240 }} role="presentation" onClick={() => setDrawerOpen(false)}>
          <Typography sx={{ px: 2, py: 1.5, fontWeight: 700 }}>Menu</Typography>
          <Divider />
          <List>
            {navLinks.map(link => (
              <ListItemButton key={link.to} component={NavLink} to={link.to}>
                <ListItemText primary={link.label} />
              </ListItemButton>
            ))}
          </List>
        </Box>
      </Drawer>

      <Box component="main" sx={{ flex: 1 }}>
        <Container maxWidth="md" sx={{ py: { xs: 2, md: 3 } }}>
          {hasSaveGame && advanceBlockReason && (
            <Alert
              role="status"
              severity="warning"
              icon={<WarningAmberIcon fontSize="inherit" />}
              sx={{ mb: 2, borderRadius: 2, '& .MuiAlert-message': { width: '100%' } }}
            >
              <Box sx={{
                display: 'flex',
                flexDirection: { xs: 'column', sm: 'row' },
                alignItems: { sm: 'center' },
                gap: { xs: 0.5, sm: 1 },
              }}>
                <Box sx={{ flex: 1 }}>{advanceBlockReason}</Box>
                {blockNoLineup && (
                  <Box component={NavLink} to={`/squad?saveGameId=${sgId}&clubId=${managedClubId}`}
                    sx={{ fontWeight: 700, color: 'inherit', whiteSpace: 'nowrap' }}>
                    Escalar time →
                  </Box>
                )}
                {!blockNoLineup && blockIneligible && (
                  <Box component={NavLink} to={`/squad?saveGameId=${sgId}&clubId=${managedClubId}`}
                    sx={{ fontWeight: 700, color: 'inherit', whiteSpace: 'nowrap' }}>
                    Ajustar escalação →
                  </Box>
                )}
                {!blockNoLineup && !blockIneligible && blockWindow && (
                  <Box component={NavLink} to={`/transfers?saveGameId=${sgId}&clubId=${managedClubId}`}
                    sx={{ fontWeight: 700, color: 'inherit', whiteSpace: 'nowrap' }}>
                    Ver propostas →
                  </Box>
                )}
              </Box>
            </Alert>
          )}
          {hasSaveGame && (preview?.lowFitnessStarters?.length ?? 0) > 0 && (
            <Alert
              severity="warning"
              icon={<WarningAmberIcon fontSize="inherit" />}
              sx={{ mb: 2, borderRadius: 2 }}
            >
              Jogadores com pouca condição:{' '}
              {(preview?.lowFitnessStarters ?? []).map(s => s.playerName).join(', ')}
            </Alert>
          )}
          <Paper
            elevation={0}
            sx={{
              bgcolor: 'rgba(18,20,29,0.94)',
              p: { xs: 2, md: 3 },
              borderRadius: 2,
              boxShadow: '0 2px 16px rgba(0,0,0,0.4)',
            }}
          >
            {children}
          </Paper>
        </Container>
      </Box>

      {/* Advance error toast */}
      <Snackbar
        open={advanceMutation.isError}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="error" variant="filled">Erro ao avançar.</Alert>
      </Snackbar>
    </>
  )
}
