import { Box, Chip, LinearProgress, Stack, Typography } from '@mui/material'
import { kitsClash } from '../pitch/kit'
import { safeKit } from './clubKit'
import { ClubCard } from './ClubCard'
import type { LiveMatchState } from '../../api/types'

/**
 * Scoreboard: home club card — score block — away club card as three equal columns (each `flex: 1`),
 * with the clock progress bar underneath.
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
  // The own card is made non-interactive at full time (disabled prop) — before this branch both
  // entry points (Substituir/Tática) were gated on !fullTime; a still-clickable own card at full
  // time let a manager queue a substitution that then failed server-side. The opponent card stays
  // clickable post-match — its scouting panel is read-only and still useful.
  //
  // Both cards render bare, name only: the kit colours and the aria-labels tell own from opponent.
  // The subs-remaining count lives in the substitutions dialog, which is where it can be acted on.

  // Guarded through safeKit before the RGB-distance check, same guard cardColors applies
  // internally — an unguarded null/non-hex primary would otherwise feed NaN into kitsClash.
  const clash = kitsClash(safeKit(state.homeClubColors).primary, safeKit(state.awayClubColors).primary)

  return (
    <Box sx={{ mb: 1 }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        <ClubCard
          shortName={state.homeClubShortName}
          colors={state.homeClubColors}
          isOwn={homeIsOwn}
          onClick={homeIsOwn ? onOwnClick : onOpponentClick}
          disabled={homeIsOwn && fullTime}
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
          isOwn={!homeIsOwn}
          onClick={homeIsOwn ? onOpponentClick : onOwnClick}
          clash={clash}
          disabled={!homeIsOwn && fullTime}
        />
      </Stack>
      {!fullTime && (
        <LinearProgress variant="determinate" value={(state.minute / clockEnd) * 100} sx={{ mt: 1 }} />
      )}
    </Box>
  )
}
