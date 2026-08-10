import { useEffect, useRef, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Typography, Button, Alert, Tabs, Tab } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import {
  getLiveMatch, advanceLiveMatch, advanceToLiveMatch, finishLiveMatch, substituteLiveMatch, changeTactics,
} from '../../api/liveMatch'
import { getPlayer, getStandings, getSaveGame } from '../../api/career'
import type { LiveEvent, LiveMatchState, LiveTacticsRequest } from '../../api/types'
import { MatchActionsDialog } from './MatchActionsDialog'
import type { SubPair } from './MatchActionsDialog'
import { PlayerRatingsList } from './PlayerRatingsList'
import { LineupsTab } from './LineupsTab'
import { MatchStatsTab } from './MatchStatsTab'
import { RoundTab } from './RoundTab'
import { MatchScoreboard } from './MatchScoreboard'
import { MatchInfoLine } from './MatchInfoLine'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'

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
  // 0 = Partida, 1 = Escalações, 2 = Atuações, 3 = Estatísticas do jogo, 4 = Rodada
  const [feedTab, setFeedTab] = useState<0 | 1 | 2 | 3 | 4>(0)
  const [applyPending, setApplyPending] = useState(false)
  const [subError, setSubError] = useState<string | null>(null)
  // True while an "advance-to" skip request is in flight. Gates the per-minute client clock
  // (see the tick effect below) so it can't race a stale setTimeout against the server-side skip.
  const [skipping, setSkipping] = useState(false)
  // The player whose read-only details modal is open, if any — clicked from a lineup or
  // ratings-row name. `selectedPlayerIsOwn` decides whether the modal shows the watch star
  // (opponents only) and is captured at click time from the side the name was rendered in.
  const [selectedPlayerId, setSelectedPlayerId] = useState<string | null>(null)
  const [selectedPlayerIsOwn, setSelectedPlayerIsOwn] = useState(true)

  const { data: state } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
  })

  const openPlayerDetails = (playerId: string, isOwnPlayer: boolean) => {
    setSelectedPlayerId(playerId)
    setSelectedPlayerIsOwn(isOwnPlayer)
  }

  const selectedPlayerQuery = useQuery({
    queryKey: ['player', saveGameId, selectedPlayerId],
    queryFn: () => getPlayer(saveGameId, selectedPlayerId!, token ?? ''),
    enabled: Boolean(saveGameId && token && selectedPlayerId),
  })

  // Pre-round standings snapshot for the Rodada tab's Classificação sub-tab. staleTime Infinity
  // so the live clock (which replaces `state` every minute) never triggers a mid-match refetch.
  // Enabled on mount regardless of the active tab — deliberately NOT owned by RoundTab, see the
  // comment on that component for why.
  const standingsQuery = useQuery({
    queryKey: ['standings', saveGameId, state?.competitionId],
    queryFn: () => getStandings(saveGameId, state!.competitionId, token ?? ''),
    enabled: Boolean(saveGameId && token && state?.competitionId),
    staleTime: Infinity,
  })

  // Shares the ['saveGame', saveGameId] cache with TablePage; used only to highlight the
  // managed club's standings row.
  const saveGameQuery = useQuery({
    queryKey: ['saveGame', saveGameId],
    queryFn: () => getSaveGame(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: 30_000,
  })
  const managedClubId = saveGameQuery.data?.managedClubId

  // Previous parallel scores keyed by fixtureId, used to flash a row when its score changes.
  // Updated after every committed render, so during render it still holds the prior snapshot.
  // Deliberately kept in the container (not moved into RoundTab): a ref owned by the tab itself
  // would reset every time the user leaves and returns to the Rodada tab, re-flashing every row.
  const prevScoresRef = useRef<Record<string, string>>({})
  useEffect(() => {
    if (!state) return
    const snapshot: Record<string, string> = {}
    for (const ps of state.parallelScores) snapshot[ps.fixtureId] = `${ps.homeGoals}-${ps.awayGoals}`
    prevScoresRef.current = snapshot
  }, [state])

  const setState = (s: LiveMatchState) => queryClient.setQueryData(['liveMatch', saveGameId], s)

  // Keyed ['liveMatch'] so the global LoadingOverlay ignores these frequent in-game calls
  // (otherwise the backdrop would flash on every auto-advance tick during play).
  const advanceMut = useMutation({
    mutationKey: ['liveMatch'],
    mutationFn: () => advanceLiveMatch(saveGameId, token ?? ''),
    onSuccess: setState,
  })

  const advanceToMut = useMutation({
    mutationKey: ['liveMatch'],
    mutationFn: (target: 'HALF_TIME' | 'FULL_TIME') => advanceToLiveMatch(saveGameId, target, token ?? ''),
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
      // Routed through resume() (declared below in this component) rather than calling
      // advanceMut.mutate() directly: resume() carries the in-flight guard, so this auto-resume
      // can't race a skip (advanceToMut) that started while the actions dialog was open — e.g.
      // the user kicks off "Avançar até o fim", then reopens the dialog and applies a sub before
      // the skip resolves.
      if (
        latest &&
        (latest.status === 'PAUSED_INJURY' || latest.status === 'PAUSED_RED' || latest.status === 'HALF_TIME')
      ) {
        resume()
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
    // Extra time is open play too. The backend flips status to FULL_TIME once the match
    // reaches its real final minute (90 or 120), so status alone terminates the clock --
    // the old `minute < 90` bound froze a knockout decider dead at the end of normal time.
    const openPlay = state.status === 'IN_PROGRESS' || state.status === 'EXTRA_TIME'
    if (playing && openPlay && !advanceMut.isPending && !actionsOpen && !skipping) {
      timer.current = setTimeout(() => advanceMut.mutate(), ADVANCE_MS)
      return () => { if (timer.current) clearTimeout(timer.current) }
    }
  }, [state, playing, advanceMut.isPending, actionsOpen, skipping])

  // "Avançar até o intervalo/fim" — skip ahead server-side to the given target. Stops the
  // pending per-minute tick immediately (both the queued setTimeout and, via `skipping`, any
  // new one the tick effect would otherwise schedule while this request is in flight) so the
  // two advance paths never race each other.
  async function skipTo(target: 'HALF_TIME' | 'FULL_TIME') {
    if (skipping || advanceToMut.isPending || advanceMut.isPending) return
    if (timer.current) { clearTimeout(timer.current); timer.current = null }
    setSkipping(true)
    setSubError(null)
    try {
      await advanceToMut.mutateAsync(target)
    } catch (err) {
      setSubError(err instanceof Error ? err.message : 'Erro ao avançar a partida.')
    } finally {
      setSkipping(false)
    }
  }

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
      setFeedTab(2)
    }
  }, [state?.status])

  if (!saveGameId) return <Typography>Partida inválida.</Typography>
  if (!state) return <Typography>Nenhuma partida em andamento.</Typography>

  const paused = state.status === 'PAUSED_INJURY' || state.status === 'PAUSED_RED'
  const halftime = state.status === 'HALF_TIME'
  const fullTime = state.status === 'FULL_TIME'
  const feed = [...state.events].reverse()
  // Resume from a forced pause / half-time by advancing past it, then let the clock run.
  // Guarded at the source (mirroring skipTo's guard) so EVERY caller — "Continuar assim
  // mesmo", "Iniciar 2º tempo", and the auto-resume in handleApply — is protected against
  // racing an in-flight skip (advanceToMut), regardless of that caller's own disabled state.
  const resume = () => {
    if (skipping || advanceToMut.isPending || advanceMut.isPending) return
    setPlaying(true)
    advanceMut.mutate()
  }

  return (
    <Box>
      <MatchScoreboard
        state={state}
        onOwnClick={() => { setActionsTab(0); setActionsOpen(true) }}
        // Task 11 wires the opponent scouting panel here; no-op for now.
        onOpponentClick={() => {}}
      />

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
          <Button
            variant="outlined"
            disabled={skipping || advanceToMut.isPending || advanceMut.isPending}
            onClick={resume}
          >
            Continuar assim mesmo
          </Button>
        )}
        {!fullTime && halftime && (
          <Button
            variant="contained"
            disabled={skipping || advanceToMut.isPending || advanceMut.isPending}
            onClick={resume}
          >
            Iniciar 2º tempo
          </Button>
        )}
        {!fullTime && !halftime && state.minute < 45 && (
          <Button
            variant="outlined"
            disabled={skipping || advanceToMut.isPending || advanceMut.isPending}
            onClick={() => skipTo('HALF_TIME')}
          >
            Avançar até o intervalo
          </Button>
        )}
        {!fullTime && (
          <Button
            variant="outlined"
            disabled={skipping || advanceToMut.isPending || advanceMut.isPending}
            onClick={() => skipTo('FULL_TIME')}
          >
            Avançar até o fim
          </Button>
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

      <Tabs value={feedTab} onChange={(_, v) => setFeedTab(v as 0 | 1 | 2 | 3 | 4)} sx={{ mt: 2, minHeight: 40 }}>
        <Tab label="Partida" sx={{ minHeight: 40 }} />
        <Tab label="Escalações" sx={{ minHeight: 40 }} />
        <Tab label="Atuações" sx={{ minHeight: 40 }} />
        <Tab label="Estatísticas do jogo" sx={{ minHeight: 40 }} />
        <Tab label="Rodada" sx={{ minHeight: 40 }} />
      </Tabs>
      <MatchInfoLine state={state} />

      {feedTab === 0 && (
        <Stack spacing={0.5} sx={{ mt: 1 }}>
          {feed.map((e, i) => (
            <Stack key={`${e.minute}-${i}`} direction="row" spacing={1}>
              <Typography sx={{ minWidth: 32, color: 'text.secondary' }}>{e.minute}'</Typography>
              <Typography component="span">{EVENT_ICON[e.type]} {e.text}</Typography>
            </Stack>
          ))}
        </Stack>
      )}

      {feedTab === 1 && (
        <Box sx={{ mt: 1 }}>
          <LineupsTab state={state} onPlayerClick={openPlayerDetails} />
        </Box>
      )}

      {feedTab === 2 && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 700, mb: 0.5 }}>Meu Time</Typography>
            {state.playerRatings.length > 0 ? (
              <PlayerRatingsList
                ratings={state.playerRatings}
                onPitchIds={new Set(state.onPitch.map(p => p.playerId))}
                isOwnPlayer
                onPlayerClick={openPlayerDetails}
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
                isOwnPlayer={false}
                onPlayerClick={openPlayerDetails}
              />
            ) : (
              <Typography sx={{ color: 'text.secondary' }}>
                As atuações aparecem conforme o jogo evolui.
              </Typography>
            )}
          </Box>
        </Stack>
      )}

      {feedTab === 3 && (
        <Box sx={{ mt: 1 }}>
          <MatchStatsTab
            stats={state.stats}
            homeShort={state.homeClubShortName}
            awayShort={state.awayClubShortName}
            homeKit={state.homeClubColors}
            awayKit={state.awayClubColors}
          />
        </Box>
      )}

      {feedTab === 4 && (
        <RoundTab
          parallelScores={state.parallelScores}
          previousScores={prevScoresRef.current}
          standingsLoading={standingsQuery.isLoading}
          standings={standingsQuery.data}
          managedClubId={managedClubId}
        />
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

      {/* Read-only player details, opened from a lineup or ratings-row name. No
          lineupControl/contractAction/listingAction here — those only make sense on the squad
          management screens. The watch star only renders for opponent-side players, and only
          once getPlayer resolves (WatchStar is never mounted with an unknown id). */}
      <PlayerDetails
        player={selectedPlayerQuery.data ?? null}
        onClose={() => setSelectedPlayerId(null)}
        saveGameId={saveGameId}
        isOwnPlayer={selectedPlayerIsOwn}
        watchAction={!selectedPlayerIsOwn && selectedPlayerQuery.data ? (
          <WatchStar saveGameId={saveGameId} playerId={selectedPlayerQuery.data.id} />
        ) : undefined}
      />
    </Box>
  )
}
