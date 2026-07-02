import { useEffect, useRef, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Typography, Button, Chip, Alert, LinearProgress } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getLiveMatch, advanceLiveMatch, finishLiveMatch } from '../../api/liveMatch'
import type { LiveEvent, LiveMatchState } from '../../api/types'

const EVENT_ICON: Record<LiveEvent['type'], string> = {
  GOAL: '⚽', SET_PIECE_GOAL: '⚽', YELLOW_CARD: '🟨', RED_CARD: '🟥',
  CHANCE: '🎯', INJURY: '🚑', SUBSTITUTION: '🔁',
}

const ADVANCE_MS = 1500   // event-to-event pacing (client-owned clock)

export function LiveMatchPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''

  const [playing, setPlaying] = useState(true)

  const { data: state } = useQuery<LiveMatchState | null>({
    queryKey: ['liveMatch', saveGameId],
    queryFn: () => getLiveMatch(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    retry: false,
  })

  const setState = (s: LiveMatchState) => queryClient.setQueryData(['liveMatch', saveGameId], s)

  const advanceMut = useMutation({
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
      void navigate(`/news?saveGameId=${saveGameId}`)
    },
  })

  // Client-owned clock: while playing and in open play, keep asking for the next event.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    if (!state) return
    const openPlay = state.status === 'IN_PROGRESS' && state.minute < 90
    if (playing && openPlay && !advanceMut.isPending) {
      timer.current = setTimeout(() => advanceMut.mutate(), ADVANCE_MS)
      return () => { if (timer.current) clearTimeout(timer.current) }
    }
  }, [state, playing, advanceMut.isPending])

  if (!saveGameId) return <Typography>Partida inválida.</Typography>
  if (!state) return <Typography>Nenhuma partida em andamento.</Typography>

  const paused = state.status === 'PAUSED_INJURY' || state.status === 'PAUSED_RED'
  const fullTime = state.status === 'FULL_TIME'
  const feed = [...state.events].reverse()

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
        <Chip color="error" label="AO VIVO" size="small" />
        <Typography sx={{ fontWeight: 700 }}>{state.minute}'</Typography>
        <Typography sx={{ flex: 1, textAlign: 'center', fontFamily: 'monospace', fontSize: '1.1rem' }}>
          {state.homeClubName} <strong>{state.homeGoals}</strong> — <strong>{state.awayGoals}</strong> {state.awayClubName}
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

      <Stack direction="row" spacing={1} sx={{ my: 1 }}>
        {!fullTime && (
          <Button variant="outlined" onClick={() => setPlaying(p => !p)}>
            {playing ? 'Pausar' : 'Continuar'}
          </Button>
        )}
        {!fullTime && paused && (
          <Button variant="outlined" onClick={() => advanceMut.mutate()}>Continuar assim mesmo</Button>
        )}
        {fullTime && (
          <Button variant="contained" disabled={finishMut.isPending} onClick={() => finishMut.mutate()}>
            {finishMut.isPending ? 'Encerrando…' : 'Ver resumo'}
          </Button>
        )}
      </Stack>

      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {feed.map((e, i) => (
          <Stack key={`${e.minute}-${i}`} direction="row" spacing={1}>
            <Typography sx={{ minWidth: 32, color: 'text.secondary' }}>{e.minute}'</Typography>
            <Typography component="span">{EVENT_ICON[e.type]} {e.text}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
