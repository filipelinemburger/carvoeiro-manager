import { useEffect, useRef, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Typography, Button, Chip, Alert, LinearProgress, Tabs, Tab } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getLiveMatch, advanceLiveMatch, finishLiveMatch, substituteLiveMatch, changeTactics } from '../../api/liveMatch'
import type { LiveEvent, LiveMatchState, LiveTacticsRequest, LivePlayer, LiveKitColors } from '../../api/types'
import { MatchActionsDialog } from './MatchActionsDialog'
import type { SubPair } from './MatchActionsDialog'
import { PlayerRatingsList } from './PlayerRatingsList'
import { PreMatchLineups } from './PreMatchLineups'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'

/** Builds one side's pitch view-model from its live on-pitch roster. Module-scope (no
 * component state) so it's freely reusable for either side without a closure. */
function buildTeam(players: LivePlayer[], formation: string, colors: LiveKitColors): TeamOnPitch {
  return {
    formation,
    kit: colors,
    showNames: true,
    players: players.map((p): PlacedPlayer => ({
      id: p.playerId,
      surname: p.name,
      position: p.position as PlacedPlayer['position'],
      fitness: p.fitness,
      badges: p.hobbled ? { injured: true } : undefined,
    })),
  }
}

const EVENT_ICON: Record<LiveEvent['type'], string> = {
  GOAL: '⚽', SET_PIECE_GOAL: '⚽', YELLOW_CARD: '🟨', RED_CARD: '🟥',
  CHANCE: '🎯', INJURY: '🚑', SUBSTITUTION: '🔁', TACTICS_CHANGE: '📋', HALF_TIME: '⏸️',
  PENALTY_GOAL: '⚽', FREE_KICK_GOAL: '⚽', CORNER_GOAL: '⚽', PENALTY_MISSED: '🧤',
}

const ADVANCE_MS = 1500   // event-to-event pacing (client-owned clock)

export function LiveMatchPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const [playing, setPlaying] = useState(true)
  const [actionsOpen, setActionsOpen] = useState(false)
  const [actionsTab, setActionsTab] = useState<0 | 1>(0)
  const [feedTab, setFeedTab] = useState<0 | 1 | 2>(0)   // 0 = Lances, 1 = Atuações, 2 = Campo
  const [applyPending, setApplyPending] = useState(false)
  const [subError, setSubError] = useState<string | null>(null)

  const { data: state } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
  })

  const setState = (s: LiveMatchState) => queryClient.setQueryData(['liveMatch', saveGameId], s)

  // Keyed ['liveMatch'] so the global LoadingOverlay ignores these frequent in-game calls
  // (otherwise the backdrop would flash on every auto-advance tick during play).
  const advanceMut = useMutation({
    mutationKey: ['liveMatch'],
    mutationFn: () => advanceLiveMatch(saveGameId, token ?? ''),
    onSuccess: setState,
  })

  const finishMut = useMutation({
    mutationFn: () => finishLiveMatch(saveGameId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['saveGame', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['news', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['standings'] })
      void queryClient.invalidateQueries({ queryKey: ['squad'] })
      void queryClient.invalidateQueries({ queryKey: ['finances'] })
      // The session was deleted on the server at full time; clear the cached live match so
      // the top-bar "Retomar partida" button doesn't linger from stale cache after the match.
      queryClient.setQueryData(['liveMatch', saveGameId], null)
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })

  // Combined apply handler: submits all queued substitutions as a single batched request,
  // then applies any tactics change. Both are raw awaits so they don't show the global
  // loading overlay (consistent with subs-only behaviour before this refactor).
  async function handleApply({ subs, tactics }: { subs: SubPair[]; tactics: LiveTacticsRequest | null }) {
    if (applyPending) return
    setApplyPending(true)
    setSubError(null)
    let latest: LiveMatchState | null = null
    try {
      if (subs.length > 0) {
        latest = await substituteLiveMatch(
          saveGameId,
          { subs: subs.map(pair => ({ outPlayerId: pair.out.playerId, inPlayerId: pair.in.playerId })) },
          token ?? '',
        )
      }
      if (tactics !== null) {
        latest = await changeTactics(saveGameId, tactics, token ?? '')
      }
      if (latest) setState(latest)
      setActionsOpen(false)
      // Resume if the match is still force-paused or at half-time after applying changes
      // (e.g. a sub made during an injury pause resolves the pause).
      if (
        latest &&
        (latest.status === 'PAUSED_INJURY' || latest.status === 'PAUSED_RED' || latest.status === 'HALF_TIME')
      ) {
        setPlaying(true)
        advanceMut.mutate()
      }
    } catch (err) {
      // Keep any partial state from successful operations so far
      if (latest) setState(latest)
      setSubError(err instanceof Error ? err.message : 'Erro ao aplicar alterações.')
    } finally {
      setApplyPending(false)
    }
  }

  // Client-owned clock: while playing and in open play, keep asking for the next event.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    if (!state) return
    const openPlay = state.status === 'IN_PROGRESS' && state.minute < 90
    if (playing && openPlay && !advanceMut.isPending && !actionsOpen) {
      timer.current = setTimeout(() => advanceMut.mutate(), ADVANCE_MS)
      return () => { if (timer.current) clearTimeout(timer.current) }
    }
  }, [state, playing, advanceMut.isPending, actionsOpen])

  // At half-time, auto-open the tactics tab once so the manager can adjust before the 2nd half.
  const halftimeOpenedRef = useRef(false)
  useEffect(() => {
    if (state?.status === 'HALF_TIME' && !halftimeOpenedRef.current) {
      halftimeOpenedRef.current = true
      setActionsTab(1)
      setActionsOpen(true)
    } else if (state?.status !== 'HALF_TIME') {
      halftimeOpenedRef.current = false
    }
  }, [state?.status])

  // At full time, surface the final grades by switching to the Atuações tab once.
  const fullTimeTabRef = useRef(false)
  useEffect(() => {
    if (state?.status === 'FULL_TIME' && !fullTimeTabRef.current) {
      fullTimeTabRef.current = true
      setFeedTab(1)
    }
  }, [state?.status])

  if (!saveGameId) return <Typography>Partida inválida.</Typography>
  if (!state) return <Typography>Nenhuma partida em andamento.</Typography>

  const paused = state.status === 'PAUSED_INJURY' || state.status === 'PAUSED_RED'
  const halftime = state.status === 'HALF_TIME'
  const fullTime = state.status === 'FULL_TIME'
  const feed = [...state.events].reverse()
  // Resume from a forced pause / half-time by advancing past it, then let the clock run.
  const resume = () => { setPlaying(true); advanceMut.mutate() }

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
        <Chip color="error" label="AO VIVO" size="small" />
        <Typography sx={{ fontWeight: 700 }}>{state.minute}'</Typography>
        <Typography sx={{ flex: 1, textAlign: 'center', fontFamily: 'monospace', fontSize: '1.1rem' }}>
          {state.homeClubShortName} <strong>{state.homeGoals}</strong> — <strong>{state.awayGoals}</strong> {state.awayClubShortName}
        </Typography>
      </Stack>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {state.competitionName}{state.round !== null ? ` · Rodada ${state.round + 1}` : ''}
      </Typography>
      {!fullTime && <LinearProgress variant="determinate" value={(state.minute / 90) * 100} sx={{ my: 1 }} />}

      {state.status === 'PAUSED_INJURY' && (
        <Alert severity="warning" sx={{ my: 1 }}>Lesão! Faça uma substituição ou continue.</Alert>
      )}
      {state.status === 'PAUSED_RED' && (
        <Alert severity="error" sx={{ my: 1 }}>Cartão vermelho! Seu time está com um a menos.</Alert>
      )}
      {halftime && (
        <Alert severity="info" sx={{ my: 1 }}>Intervalo — ajuste as táticas e comece o segundo tempo.</Alert>
      )}
      {subError && (
        <Alert severity="error" sx={{ my: 1 }} onClose={() => setSubError(null)}>{subError}</Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ my: 1 }}>
        {!fullTime && !halftime && (
          <Button variant="outlined" onClick={() => setPlaying(p => !p)}>
            {playing ? 'Pausar' : 'Continuar'}
          </Button>
        )}
        {!fullTime && paused && (
          <Button variant="outlined" onClick={resume}>Continuar assim mesmo</Button>
        )}
        {!fullTime && halftime && (
          <Button variant="contained" onClick={resume}>Iniciar 2º tempo</Button>
        )}
        {!fullTime && (
          <Button variant="outlined" color="secondary" disabled={state.subsRemaining <= 0}
            onClick={() => { setActionsTab(0); setActionsOpen(true) }}>
            Substituir ({state.subsRemaining})
          </Button>
        )}
        {!fullTime && (
          <Button variant="outlined" color="secondary" onClick={() => { setActionsTab(1); setActionsOpen(true) }}>
            Tática
          </Button>
        )}
        {fullTime && (
          <Button variant="contained" disabled={finishMut.isPending} onClick={() => finishMut.mutate()}>
            {finishMut.isPending ? 'Continuando…' : 'Continuar'}
          </Button>
        )}
      </Stack>

      <Tabs value={feedTab} onChange={(_, v) => setFeedTab(v as 0 | 1 | 2)} sx={{ mt: 2, minHeight: 40 }}>
        <Tab label="Lances" sx={{ minHeight: 40 }} />
        <Tab label="Atuações" sx={{ minHeight: 40 }} />
        <Tab label="Campo" sx={{ minHeight: 40 }} />
      </Tabs>

      {feedTab === 0 && (
        state.minute === 0 ? (
          <PreMatchLineups
            home={{
              clubName: state.homeClubShortName,
              formation: state.managedIsHome ? state.currentTactics.formation : state.opponentTactics.formation,
              players: state.managedIsHome ? state.onPitch : state.opponentOnPitch,
            }}
            away={{
              clubName: state.awayClubShortName,
              formation: state.managedIsHome ? state.opponentTactics.formation : state.currentTactics.formation,
              players: state.managedIsHome ? state.opponentOnPitch : state.onPitch,
            }}
          />
        ) : (
          <Stack spacing={0.5} sx={{ mt: 1 }}>
            {feed.map((e, i) => (
              <Stack key={`${e.minute}-${i}`} direction="row" spacing={1}>
                <Typography sx={{ minWidth: 32, color: 'text.secondary' }}>{e.minute}'</Typography>
                <Typography component="span">{EVENT_ICON[e.type]} {e.text}</Typography>
              </Stack>
            ))}
          </Stack>
        )
      )}

      {feedTab === 1 && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 700, mb: 0.5 }}>Meu Time</Typography>
            {state.playerRatings.length > 0 ? (
              <PlayerRatingsList
                ratings={state.playerRatings}
                onPitchIds={new Set(state.onPitch.map(p => p.playerId))}
              />
            ) : (
              <Typography sx={{ color: 'text.secondary' }}>
                As atuações aparecem conforme o jogo evolui.
              </Typography>
            )}
          </Box>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 700, mb: 0.5 }}>Adversário</Typography>
            {state.opponentRatings.length > 0 ? (
              <PlayerRatingsList
                ratings={state.opponentRatings}
                onPitchIds={new Set(state.opponentOnPitch.map(p => p.playerId))}
              />
            ) : (
              <Typography sx={{ color: 'text.secondary' }}>
                As atuações aparecem conforme o jogo evolui.
              </Typography>
            )}
          </Box>
        </Stack>
      )}

      {feedTab === 2 && (
        <Box sx={{ mt: 1 }}>
          <Pitch
            home={buildTeam(
              state.managedIsHome ? state.onPitch : state.opponentOnPitch,
              state.managedIsHome ? state.currentTactics.formation : state.opponentTactics.formation,
              state.homeClubColors,
            )}
            away={buildTeam(
              state.managedIsHome ? state.opponentOnPitch : state.onPitch,
              state.managedIsHome ? state.opponentTactics.formation : state.currentTactics.formation,
              state.awayClubColors,
            )}
          />
        </Box>
      )}

      <MatchActionsDialog
        key={String(actionsOpen)}
        open={actionsOpen}
        onClose={() => setActionsOpen(false)}
        initialTab={actionsTab}
        onPitch={state.onPitch}
        bench={state.bench}
        subsRemaining={state.subsRemaining}
        pending={applyPending}
        onApply={handleApply}
        currentTactics={state.currentTactics}
      />
    </Box>
  )
}
