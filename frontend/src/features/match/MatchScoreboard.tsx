import { Box, Chip, LinearProgress, Stack, Typography } from '@mui/material'
import { kitsClash } from '../pitch/kit'
import { safeKit } from './clubKit'
import { ClubCard } from './ClubCard'
import type { LiveMatchState } from '../../api/types'

/**
 * Scoreboard: home club card — score block — away club card, cards at the outer edges and the
 * score centred between them, with the clock progress bar underneath.
 *
 * "Own" (the managed club) is decided from `state.managedIsHome`, never assumed to be home —
 * `onOwnClick`/`onOpponentClick` are wired to whichever side (home or away) that turns out to be.
 * `homeClubColors`/`awayClubColors` are already home/away oriented and are passed straight
 * through unflipped, same contract as the rest of the match screen (see `matchSides.ts`).
 */
export function MatchScoreboard({ state, onOwnClick, onOpponentClick }: {
  state: LiveMatchState
  onOwnClick: () => void
  onOpponentClick: () => void
}) {
  const fullTime = state.status === 'FULL_TIME'
  // A knockout decider level at 90' runs to 120', so the progress bar's denominator is not
  // always 90. The state carries no finalMinute, so derive it: any minute past 90 (or an
  // EXTRA_TIME status) means this match is running the full 120.
  const clockEnd = state.status === 'EXTRA_TIME' || state.minute > 90 ? 120 : 90

  const homeIsOwn = state.managedIsHome
  // No subs remain meaningful once the match is over — hide the caption rather than show a
  // stale count.
  const ownCaption = fullTime ? undefined : `Substituições (${state.subsRemaining})`

  // Guarded through safeKit before the RGB-distance check, same guard cardColors applies
  // internally — an unguarded null/non-hex primary would otherwise feed NaN into kitsClash.
  const clash = kitsClash(safeKit(state.homeClubColors).primary, safeKit(state.awayClubColors).primary)

  return (
    <Box sx={{ mb: 1 }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <ClubCard
          shortName={state.homeClubShortName}
          colors={state.homeClubColors}
          caption={homeIsOwn ? ownCaption : 'Adversário'}
          isOwn={homeIsOwn}
          onClick={homeIsOwn ? onOwnClick : onOpponentClick}
        />
        <Stack spacing={0.5} sx={{ flex: 1, alignItems: 'center' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Chip color="error" label="AO VIVO" size="small" />
            <Typography sx={{ fontWeight: 700 }}>{state.minute}'</Typography>
          </Stack>
          <Typography sx={{ fontFamily: 'monospace', fontSize: '1.1rem' }}>
            {state.homeGoals} — {state.awayGoals}
          </Typography>
        </Stack>
        <ClubCard
          shortName={state.awayClubShortName}
          colors={state.awayClubColors}
          caption={homeIsOwn ? 'Adversário' : ownCaption}
          isOwn={!homeIsOwn}
          onClick={homeIsOwn ? onOpponentClick : onOwnClick}
          clash={clash}
        />
      </Stack>
      {!fullTime && (
        <LinearProgress variant="determinate" value={(state.minute / clockEnd) * 100} sx={{ mt: 1 }} />
      )}
    </Box>
  )
}
