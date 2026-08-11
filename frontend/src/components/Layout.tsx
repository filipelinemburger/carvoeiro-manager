import { useState, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AppBar, Toolbar, Box, Button, IconButton, Typography, Menu, MenuItem,
  Drawer, List, ListItemButton, ListItemText, Alert, Snackbar, Container, Paper, Divider, Badge,
  CircularProgress,
} from '@mui/material'
import MenuIcon from '@mui/icons-material/Menu'
import SettingsIcon from '@mui/icons-material/Settings'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import SportsSoccerIcon from '@mui/icons-material/SportsSoccer'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import FastForwardIcon from '@mui/icons-material/FastForward'
import { useIsMobile } from '../lib/useIsMobile'
import { useAuth } from '../auth/AuthContext'
import { advanceRound, getAdvancePreview, getLineup, getSaveGame } from '../api/career'
import { deleteAccount } from '../api/account'
import { getLiveMatch, kickoffLiveMatch } from '../api/liveMatch'
import { getNewsUnreadCount } from '../api/news'
import { formatDatePtBR } from '../utils/date'
import { saveGameKeyFor } from '../features/career/saveGameKey'
import { LoadCareerDialog } from '../features/career/LoadCareerDialog'
import { NewCareerDialog } from '../features/career/NewCareerDialog'
import { ConfirmDialog } from './ConfirmDialog'
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

  const { data: newsUnread = 0 } = useQuery<number>({
    queryKey: ['newsUnreadCount', saveGameId],
    queryFn: () => getNewsUnreadCount(saveGameId!, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
    staleTime: 30_000,
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

  const isMobile = useIsMobile()

  const [gearAnchor, setGearAnchor] = useState<null | HTMLElement>(null)
  const gearOpen = Boolean(gearAnchor)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [loadCareerOpen, setLoadCareerOpen] = useState(false)
  const [newCareerOpen, setNewCareerOpen] = useState(false)
  // Confirms before kicking off the managed club's own match — advancing straight into a
  // match is a bigger commitment (locks the lineup) than a routine day/round advance.
  const [confirmKickoffOpen, setConfirmKickoffOpen] = useState(false)

  const advanceMutation = useMutation<AdvanceResponse>({
    mutationFn: () => advanceRound(saveGameId!, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['newsUnreadCount', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      // An advance can play a knockout leg or materialize a whole round, so the bracket and
      // the phases' `complete` flags both go stale. Both are cached with staleTime: Infinity,
      // so nothing else will ever refetch them.
      void queryClient.invalidateQueries({ queryKey: ['phases'] })
      void queryClient.invalidateQueries({ queryKey: ['ties'] })
      void queryClient.invalidateQueries({ queryKey: ['finances'] })
      void queryClient.invalidateQueries({ queryKey: ['offers'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      void queryClient.invalidateQueries({ queryKey: ['market'] })
      void queryClient.invalidateQueries({ queryKey: ['clubs'] })
      void queryClient.invalidateQueries({ queryKey: ['staffCandidates'] })
      // Advancing processes TRANSFER days — renewals/locks/pokes change both pre-contract lists.
      void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
      // Advancing can sell/list/free watched players, changing their status.
      void queryClient.invalidateQueries({ queryKey: ['watchlist', saveGameId] })
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
    if (preview?.managedClubPlaysNext) { setConfirmKickoffOpen(true); return }
    advanceMutation.mutate()
  }

  const handleConfirmKickoff = () => {
    setConfirmKickoffOpen(false)
    // Re-validate: a background preview refetch may have flipped managedClubPlaysNext
    // (e.g. the fixture was postponed) while the dialog was open.
    if (!preview?.managedClubPlaysNext) return
    kickoffMutation.mutate()
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

  // The advance control's four states. On compact viewports the label is rendered as an icon
  // (see below), so each state also names the icon that stands in for its words.
  const advancePending = advanceMutation.isPending || kickoffMutation.isPending
  const advanceLabel = liveMatch ? 'Retomar partida'
    : advancePending ? 'Avançando…'
      : preview?.managedClubPlaysNext ? 'Ir para o jogo'
        : 'Avançar'
  const advanceIcon = advancePending ? <CircularProgress size={20} color="inherit" />
    : liveMatch ? <PlayArrowIcon />
      : preview?.managedClubPlaysNext ? <SportsSoccerIcon />
        : <FastForwardIcon />

  // Section links shown once a save game exists — reused by the desktop bar and the
  // mobile Drawer so the two never drift.
  const saveLinks = hasSaveGame
    ? [
        { to: `/squad?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Escalação' },
        { to: `/base?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Base' },
        { to: `/table?saveGameId=${sgId}&competitionId=${standingsCompetitionId}&managedClubId=${managedClubId}`, label: 'Classificação' },
        { to: `/calendar?saveGameId=${sgId}&competitionId=${standingsCompetitionId}`, label: 'Calendário' },
        { to: `/clubs?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Clubes' },
        { to: `/finances?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Finanças' },
        { to: `/staff?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Comissão Técnica' },
        { to: `/transfers?saveGameId=${sgId}&clubId=${managedClubId}`, label: 'Mercado' },
        { to: `/history?saveGameId=${sgId}`, label: 'Histórico' },
        { to: `/news?saveGameId=${sgId}`, label: 'News', badge: newsUnread },
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

  const renderNavLabel = (link: { label: string; badge?: number }) =>
    link.badge ? (
      <Badge
        color="error"
        badgeContent={link.badge}
        max={99}
        sx={{ '& .MuiBadge-badge': { position: 'static', transform: 'none', ml: 1 } }}
      >
        <Box component="span">{link.label}</Box>
      </Badge>
    ) : (
      link.label
    )

  return (
    <>
      <AppBar position="sticky" elevation={0} sx={{ bgcolor: BAR_BG, color: BAR_FG }}>
        <Toolbar variant="dense" sx={{ gap: { xs: 0.5, md: 1 } }}>
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

          {/* Shortened on phones: the full name plus the date and three icon buttons overflow a
              375px bar, which is what pushed the menu and gear off screen. */}
          <Typography component="span" sx={{ fontWeight: 700, mr: { md: 1 }, whiteSpace: 'nowrap' }}>
            {isMobile ? 'Carvoeiro' : 'Carvoeiro Manager'}
          </Typography>

          {/* Desktop: inline nav links. The full set is wider than a 1280px laptop once the
              brand, date and advance button are accounted for, so the strip scrolls (without a
              visible scrollbar) rather than squeezing its neighbours. */}
          <Box
            component="nav"
            sx={{
              display: { xs: 'none', md: 'flex' },
              alignItems: 'center',
              gap: 0.25,
              minWidth: 0,
              overflowX: 'auto',
              scrollbarWidth: 'none',
              '&::-webkit-scrollbar': { display: 'none' },
            }}
          >
            {navLinks.map(link => (
              <Box key={link.to} component={NavLink} to={link.to} sx={barLinkSx}>
                {renderNavLabel(link)}
              </Box>
            ))}
          </Box>

          <Box sx={{ flexGrow: 1 }} />

          {/* Current in-game date: always visible whenever a save game is active, so the
              manager can see where they are in the calendar from anywhere in the app. */}
          {hasSaveGame && saveGame?.currentDate && (
            <Typography
              component="span"
              variant="caption"
              sx={{ color: BAR_FG, opacity: 0.85, mr: 1, whiteSpace: 'nowrap' }}
            >
              {formatDatePtBR(saveGame.currentDate)}
            </Typography>
          )}

          {/* The label is long enough ("Ir para o jogo") to wrap onto four lines on a phone,
              inflating the bar and pushing the menu/gear icons off screen — so on compact
              viewports the same action becomes a gold icon button, named for screen readers. */}
          {hasSaveGame && !isMatchScreen && (
            isMobile ? (
              <IconButton
                disabled={advanceDisabled || kickoffMutation.isPending}
                aria-label={advanceLabel}
                title={advanceBlockReason ?? advanceLabel}
                onClick={handleAdvance}
                sx={{
                  bgcolor: 'primary.main',
                  color: 'primary.contrastText',
                  '&:hover': { bgcolor: 'primary.dark' },
                  '&.Mui-disabled': { bgcolor: 'action.disabledBackground', color: 'action.disabled' },
                }}
              >
                {advanceIcon}
              </IconButton>
            ) : (
              <Button
                color="primary"
                disabled={advanceDisabled || kickoffMutation.isPending}
                title={advanceBlockReason ?? undefined}
                onClick={handleAdvance}
                sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}
              >
                {advanceLabel}
              </Button>
            )
          )}

          {hasSaveGame && !isMatchScreen && (
            <ConfirmDialog
              open={confirmKickoffOpen}
              title="Ir para o jogo"
              message="Começar a partida contra o próximo adversário?"
              confirmLabel="Começar partida"
              onConfirm={handleConfirmKickoff}
              onClose={() => setConfirmKickoffOpen(false)}
              confirmDisabled={kickoffMutation.isPending}
            />
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
                <ListItemText primary={renderNavLabel(link)} />
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
