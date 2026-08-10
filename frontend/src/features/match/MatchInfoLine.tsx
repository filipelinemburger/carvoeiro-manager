import { Stack, Typography } from '@mui/material'
import { formatDatePtBR } from '../../utils/date'
import { formatThousandsBR } from '../../lib/money'
import { WeatherChip, type WeatherCondition } from '../../components/WeatherChip'
import type { LiveMatchState } from '../../api/types'

/**
 * The game-info caption row: competition/round/stadium/date, weather and crowd size. Moved
 * verbatim (same behaviour, same null-safety) from `LiveMatchPage.tsx`'s old caption `Stack`.
 *
 * Stadium and date are kept inside the SAME `Typography` on purpose — tests match them as one
 * text node (see `getNodeText`'s direct-child-text-node semantics), so splitting them into
 * separate elements would silently break that assertion.
 */
export function MatchInfoLine({ state }: { state: LiveMatchState }) {
  return (
    <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {state.competitionName}{state.round !== null ? ` · Rodada ${state.round + 1}` : ''}
        {state.matchDate
          ? ` · ${state.stadiumName ? `${state.stadiumName} · ` : ''}${formatDatePtBR(state.matchDate)}`
          : ''}
      </Typography>
      <WeatherChip condition={state.weather as WeatherCondition | null} temperatureC={state.temperatureC} />
      {state.attendance !== null && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Público: {formatThousandsBR(state.attendance)}
        </Typography>
      )}
    </Stack>
  )
}
