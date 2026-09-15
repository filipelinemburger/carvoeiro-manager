import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Typography, Button, Alert, Tabs, Tab } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import {
  getLiveMatch, advanceLiveMatch, advanceToLiveMatch, finishLiveMatch, substituteLiveMatch, changeTactics,
} from '../../api/liveMatch'
import { getPlayer, getStandings, getSaveGame } from '../../api/career'
import type { LiveMatchState, LiveTacticsRequest } from '../../api/types'
import { MatchActionsDialog } from './MatchActionsDialog'
import type { SubPair } from './MatchActionsDialog'
import { PlayerRatingsList } from './PlayerRatingsList'
import { LineupsTab } from './LineupsTab'
import { MatchStatsTab } from './MatchStatsTab'
import { RoundTab } from './RoundTab'
import { MatchScoreboard } from './MatchScoreboard'
import { MatchInfoLine } from './MatchInfoLine'
import { MatchNarrationCard, actionSlotShowsButton } from './MatchNarrationCard'
import { GameOptionsMenu } from './GameOptionsMenu'
import { OpponentScoutingPanel } from './OpponentScoutingPanel'
import { sidesOf } from './matchSides'
import { liveStandings } from './liveStandings'
import type { LiveResult } from './liveStandings'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'

const ADVANCE_MS = 1500   // event-to-event pacing (client-owned clock)

export function LiveMatchPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const [playing, setPlaying] = useState(false)
  const [actionsOpen, setActionsOpen] = useState(false)
  const [actionsTab, setActionsTab] = useState<0 | 1>(0)
  // Opens the read-only opponent scouting panel from the opponent's club card.
  const [scoutingOpen, setScoutingOpen] = useState(false)
  // Mirrors GameOptionsMenu's own MUI Menu open state, via its onOpenChange callback — gates the
  // per-minute clock below, same reasoning as actionsOpen/scoutingOpen: a backdrop-covering
  // surface whose items' disabled state (actionPending) flips every tick must not have the clock
  // ticking behind it while it's open.
  const [gameOptionsMenuOpen, setGameOptionsMenuOpen] = useState(false)
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

  // Pre-round standings snapshot, the BASE the live table is built on. staleTime Infinity so the
  // live clock (which replaces `state` every minute) never triggers a mid-match refetch — and
  // because the server's answer genuinely cannot change while the match runs: this round's
  // fixtures are only written back at finish(). Enabled on mount regardless of the active tab —
  // deliberately NOT owned by RoundTab, see the comment on that component for why.
  const standingsQuery = useQuery({
    queryKey: ['standings', saveGameId, state?.competitionId],
    queryFn: () => getStandings(saveGameId, state!.competitionId, token ?? ''),
    enabled: Boolean(saveGameId && token && state?.competitionId),
    staleTime: Infinity,
  })

  // The live table: pre-round base + this match's score + every parallel fixture's score.
  // Recomputed on each tick (that is the point), but off the cached base — `liveStandings` is
  // pure, so the React Query cache the base lives in is never written through.
  // Undefined base stays undefined so LiveStandingsTable keeps its isLoading/empty branches.
  const liveTable = useMemo(() => {
    const base = standingsQuery.data
    if (!base || !state) return base
    // A parallel fixture the backend could not resolve carries null ids (both, always together);
    // `!= null` also covers a fixture serialized before the ids existed, which arrives undefined.
    const parallel: LiveResult[] = state.parallelScores.flatMap(ps =>
      ps.homeClubId != null && ps.awayClubId != null
        ? [{ homeClubId: ps.homeClubId, awayClubId: ps.awayClubId, homeGoals: ps.homeGoals, awayGoals: ps.awayGoals }]
        : [])
    return liveStandings(base, [
      { homeClubId: state.homeClubId, awayClubId: state.awayClubId, homeGoals: state.homeGoals, awayGoals: state.awayGoals },
      ...parallel,
    ])
  }, [standingsQuery.data, state])

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
    // Every backdrop-covering surface gates the clock: the actions dialog, an in-flight skip, the
    // scouting panel, the read-only player details modal (selectedPlayerId !== null — opened from
    // a lineup or ratings-row name, a first-class interaction now that Escalações is its own tab)
    // and the game-options menu (its items' disabled state flips every tick behind the backdrop).
    if (
      playing && openPlay && !advanceMut.isPending && !actionsOpen && !skipping && !scoutingOpen
      && selectedPlayerId === null && !gameOptionsMenuOpen
    ) {
      timer.current = setTimeout(() => advanceMut.mutate(), ADVANCE_MS)
      return () => { if (timer.current) clearTimeout(timer.current) }
    }
  }, [
    state, playing, advanceMut.isPending, actionsOpen, skipping, scoutingOpen, selectedPlayerId,
    gameOptionsMenuOpen,
  ])

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

  // At full time, surface the final grades by switching to the Atuações tab once.
  const fullTimeTabRef = useRef(false)
  useEffect(() => {
    if (state?.status === 'FULL_TIME' && !fullTimeTabRef.current) {
      fullTimeTabRef.current = true
      setFeedTab(2)
    }
  }, [state?.status])

  // `playing` starts false so minute 0 waits for "Iniciar partida". A RESUMED match (reload,
  // or the top bar's "Retomar partida") has no such button, so restore the previous auto-play
  // behaviour exactly once, the first time a live state with a started match is observed.
  const autoResumedRef = useRef(false)
  useEffect(() => {
    if (!state || autoResumedRef.current) return
    autoResumedRef.current = true
    if (state.minute > 0 && (state.status === 'IN_PROGRESS' || state.status === 'EXTRA_TIME')) setPlaying(true)
  }, [state])

  if (!saveGameId) return <Typography>Partida inválida.</Typography>
  if (!state) return <Typography>Nenhuma partida em andamento.</Typography>

  // The opponent scouting panel's SideView, resolved through sidesOf (matchSides.ts) — never a
  // hand-rolled home/away mapping — so it can't accidentally show the managed club's own side.
  const opponentSide = state.managedIsHome ? sidesOf(state).away : sidesOf(state).home

  const halftime = state.status === 'HALF_TIME'
  // Single source of truth for "does the Partida card show a button right now" — imported from
  // MatchNarrationCard, where ActionSlot calls the very same function, so the two can never drift
  // (see the doc comment on actionSlotShowsButton). Drives (a) the pause/play toggle's visibility
  // below — hidden, not disabled, both because pausing is meaningless in every one of those
  // states and because the full-time card button and the pause toggle can share the label
  // "Continuar", which would make `getByRole('button', { name: /continuar/i })` ambiguous if both
  // were mounted at once — and (b) the Partida card's visibility on the other four tabs, so
  // kickoff/half-time/both forced pauses stay reachable no matter which tab the manager is on.
  const showsActionButton = actionSlotShowsButton(state.minute, state.status)
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
        onOpponentClick={() => setScoutingOpen(true)}
      />

      {halftime && (
        <Alert severity="info" sx={{ my: 1 }}>Intervalo — ajuste as táticas e comece o segundo tempo.</Alert>
      )}
      {subError && (
        <Alert severity="error" sx={{ my: 1 }} onClose={() => setSubError(null)}>{subError}</Alert>
      )}

      {/* Pausar/Continuar, Continuar assim mesmo, Iniciar 2º tempo and the full-time Continuar
          now live on MatchNarrationCard (Partida tab) — see the action-slot table there. The two
          "Avançar" skip actions live in GameOptionsMenu (bottom of the page). Substituir/Tática
          no longer have their own buttons — the own club card (above) is their entry point. */}

      {/* Part of the HEADER — deliberately above <Tabs>, not below it. Below the tab bar it
          rendered on top of whichever tab panel was selected, so the Partida game details were
          repeated on all five tabs; above it, the line belongs to the club names/score block and
          is stated once. LiveMatchPage.test.tsx pins this DOM order. */}
      <MatchInfoLine state={state} />

      <Tabs value={feedTab} onChange={(_, v) => setFeedTab(v as 0 | 1 | 2 | 3 | 4)} sx={{ mt: 2, minHeight: 40 }}>
        <Tab label="Partida" sx={{ minHeight: 40 }} />
        <Tab label="Escalações" sx={{ minHeight: 40 }} />
        <Tab label="Atuações" sx={{ minHeight: 40 }} />
        <Tab label="Estatísticas do jogo" sx={{ minHeight: 40 }} />
        <Tab label="Rodada" sx={{ minHeight: 40 }} />
      </Tabs>

      {/* Also rendered outside Partida (feedTab 0) whenever the action slot shows a button
          (showsActionButton — kickoff, half-time, both forced pauses, and full time): every
          kickoff/resume/pause control and both pause Alerts live only on this card, and browsing
          the other tabs is normal mid-match activity now (the clock deliberately keeps running
          there), so the card the match is waiting on must stay reachable no matter which tab the
          manager is on. This subsumes the old fullTime-only special case — the fullTimeTabRef
          effect above auto-switches to Atuações at full time, which would otherwise strand the
          finish "Continuar" button (now living on this card) on a tab nobody is looking at, same
          as it subsumes minute 0 (Escalações has no other kickoff affordance) and both pauses
          (before this branch, both Alerts rendered unconditionally at page level). */}
      {(feedTab === 0 || showsActionButton) && (
        <Box sx={{ mt: 1 }}>
          <MatchNarrationCard
            minute={state.minute}
            status={state.status}
            events={state.events}
            // Home/away oriented, NOT managed/opponent — the card is tinted by which SIDE the
            // latest event belongs to (`event.home`), so these must be passed straight through
            // in the same orientation, exactly as MatchScoreboard/MatchStatsTab do.
            homeKit={state.homeClubColors}
            awayKit={state.awayClubColors}
            onResume={resume}
            onFinish={() => finishMut.mutate()}
            actionPending={skipping || advanceToMut.isPending || advanceMut.isPending}
            finishPending={finishMut.isPending}
          />
          {!showsActionButton && (
            <Stack direction="row" sx={{ justifyContent: 'center', mt: 1 }}>
              <Button size="small" variant="outlined" onClick={() => setPlaying(p => !p)}>
                {playing ? 'Pausar' : 'Continuar'}
              </Button>
            </Stack>
          )}
        </Box>
      )}

      {feedTab === 1 && (
        <Box sx={{ mt: 1 }}>
          <LineupsTab state={state} onPlayerClick={openPlayerDetails} />
        </Box>
      )}

      {/* Home LEFT, away RIGHT — always, matching the scoreboard above. These columns used to be
          managed/opponent oriented ("Meu Time" left), which contradicted the scoreboard whenever
          the managed club played away. Both sides therefore come from sidesOf(state), never from
          state.playerRatings/opponentRatings directly — reaching for those is what made the
          layout managed-oriented. `isOwnPlayer` follows side.isOwnSide, NOT the column position:
          it drives the watch star in the details modal (opponents only), so deriving it from the
          position would star the manager's own players whenever they are the home side. */}
      {feedTab === 2 && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 1 }}>
          {(['home', 'away'] as const).map(slot => {
            const side = sidesOf(state)[slot]
            return (
              <Box key={slot} sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontWeight: 700, mb: 0.5 }}>{side.shortName}</Typography>
                {side.ratings.length > 0 ? (
                  <PlayerRatingsList
                    ratings={side.ratings}
                    onPitchIds={new Set(side.players.map(p => p.playerId))}
                    isOwnPlayer={side.isOwnSide}
                    onPlayerClick={openPlayerDetails}
                  />
                ) : (
                  <Typography sx={{ color: 'text.secondary' }}>
                    As atuações aparecem conforme o jogo evolui.
                  </Typography>
                )}
              </Box>
            )
          })}
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
          standings={liveTable}
          managedClubId={managedClubId}
        />
      )}

      <GameOptionsMenu
        minute={state.minute}
        status={state.status}
        actionPending={skipping || advanceToMut.isPending || advanceMut.isPending}
        onSkip={skipTo}
        onOpenChange={setGameOptionsMenuOpen}
      />

      <MatchActionsDialog
        key={String(actionsOpen)}
        open={actionsOpen}
        onClose={() => setActionsOpen(false)}
        initialTab={actionsTab}
        onPitch={state.onPitch}
        bench={state.bench}
        events={state.events}
        minute={state.minute}
        subsRemaining={state.subsRemaining}
        pending={applyPending}
        onApply={handleApply}
        currentTactics={state.currentTactics}
        kit={state.managedIsHome ? state.homeClubColors : state.awayClubColors}
      />

      <OpponentScoutingPanel
        open={scoutingOpen}
        onClose={() => setScoutingOpen(false)}
        side={opponentSide}
        tactics={state.opponentTactics}
        onPlayerClick={openPlayerDetails}
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
