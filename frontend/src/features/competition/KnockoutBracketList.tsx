import { Box, Card, CardContent, Stack, Typography } from '@mui/material'
import type { KnockoutTie } from '../../api/types'

type Props = {
  phaseName: string
  ties: KnockoutTie[]
  managedClubId: string
}

/**
 * Aggregate over both legs, from each seed's perspective. The higher seed is away in leg 1
 * and home in leg 2 (it hosts the second leg), so the crossover is:
 *   higherAggregate = firstLegAwayGoals + secondLegHomeGoals
 *   lowerAggregate  = firstLegHomeGoals + secondLegAwayGoals
 * Null (any leg unplayed) means "no aggregate to show yet" rather than a crash.
 */
function aggregate(tie: KnockoutTie): { higher: number; lower: number } | null {
  const { firstLegHomeGoals, firstLegAwayGoals, secondLegHomeGoals, secondLegAwayGoals } = tie
  if (
    firstLegHomeGoals == null || firstLegAwayGoals == null ||
    secondLegHomeGoals == null || secondLegAwayGoals == null
  ) {
    return null
  }
  return {
    higher: firstLegAwayGoals + secondLegHomeGoals,
    lower: firstLegHomeGoals + secondLegAwayGoals,
  }
}

/** One seed's name within a tie card, bold when it won, marked with data-managed for the club the user manages. */
function TieClubName({ clubId, clubName, managedClubId, isWinner }: {
  // clubName is nullable on the wire: KnockoutTieService sends null when the club row cannot be
  // resolved. Fall back to a placeholder rather than rendering an empty line.
  clubId: string; clubName: string | null; managedClubId: string; isWinner: boolean
}) {
  return (
    <Typography
      data-testid={`tie-club-${clubId}`}
      data-managed={String(clubId === managedClubId)}
      sx={{ fontWeight: isWinner ? 700 : 400 }}
    >
      {clubName ?? '—'}
    </Typography>
  )
}

/**
 * A knockout phase's ties, grouped under the phase name. Each card shows both seeds, the
 * two-leg aggregate (once both legs are played), and the penalty shootout score when the tie
 * was decided that way. A tie that hasn't been played yet (any/all goal fields null) renders
 * with no score at all rather than crashing — this endpoint is called mid-season with half the
 * bracket unplayed.
 */
export function KnockoutBracketList({ phaseName, ties, managedClubId }: Props) {
  const orderedTies = [...ties].sort((a, b) => a.tieOrder - b.tieOrder)

  return (
    <Stack spacing={1.5}>
      <Typography variant="h6" component="h3" sx={{ fontWeight: 700 }}>
        {phaseName}
      </Typography>
      {/* A knockout phase's ties only exist once the previous phase finishes, so a reached-but-
          undrawn round would otherwise render as a bare heading over whitespace. */}
      {orderedTies.length === 0 && (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Confrontos ainda não definidos.
        </Typography>
      )}
      {orderedTies.map(tie => {
        const agg = aggregate(tie)
        const hasPenalties = tie.penaltyHome != null && tie.penaltyAway != null
        return (
          <Card key={tie.tieId} variant="outlined">
            <CardContent>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ justifyContent: 'space-between' }}>
                <Box>
                  <TieClubName
                    clubId={tie.higherSeedClubId}
                    clubName={tie.higherSeedClubName}
                    managedClubId={managedClubId}
                    isWinner={tie.winnerClubId === tie.higherSeedClubId}
                  />
                  <TieClubName
                    clubId={tie.lowerSeedClubId}
                    clubName={tie.lowerSeedClubName}
                    managedClubId={managedClubId}
                    isWinner={tie.winnerClubId === tie.lowerSeedClubId}
                  />
                </Box>
                <Box sx={{ textAlign: { xs: 'left', sm: 'right' } }}>
                  {agg && <Typography sx={{ fontWeight: 600 }}>{`${agg.higher} x ${agg.lower}`}</Typography>}
                  {hasPenalties && (
                    <Typography variant="body2" color="text.secondary">
                      {`${tie.penaltyHome} x ${tie.penaltyAway} nos pênaltis`}
                    </Typography>
                  )}
                </Box>
              </Stack>
            </CardContent>
          </Card>
        )
      })}
    </Stack>
  )
}
