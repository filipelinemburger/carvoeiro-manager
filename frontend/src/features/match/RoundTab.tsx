import { useState } from 'react'
import { Box, Tabs, Tab } from '@mui/material'
import { ParallelScoresList } from './ParallelScoresList'
import { LiveStandingsTable } from './LiveStandingsTable'
import type { ParallelScore, StandingRow } from '../../api/types'

/**
 * "Rodada" — nested Resultados (default) / Classificação sub-tabs.
 *
 * `previousScores` and the standings `{ isLoading, data }` pair are owned by the container
 * (`LiveMatchPage`) and passed straight through rather than fetched or tracked here: see the
 * comments on `prevScoresRef` and `standingsQuery` in `LiveMatchPage.tsx` for the regressions
 * that moving either down into this component would reintroduce.
 *
 * `standings` is already the LIVE table (`liveTable` in the container, via `liveStandings`) —
 * this component renders rows, it does not fetch them and does not fold the round's scores in.
 */
export function RoundTab({ parallelScores, previousScores, standingsLoading, standings, managedClubId }: {
  parallelScores: ParallelScore[]
  previousScores: Record<string, string>
  standingsLoading: boolean
  standings: StandingRow[] | undefined
  managedClubId: string | undefined
}) {
  const [subTab, setSubTab] = useState<0 | 1>(0)

  return (
    <Box sx={{ mt: 1 }}>
      <Tabs value={subTab} onChange={(_, v) => setSubTab(v as 0 | 1)} sx={{ minHeight: 36 }}>
        <Tab label="Resultados" sx={{ minHeight: 36 }} />
        <Tab label="Classificação" sx={{ minHeight: 36 }} />
      </Tabs>
      {subTab === 0 && (
        <ParallelScoresList parallelScores={parallelScores} previousScores={previousScores} />
      )}
      {subTab === 1 && (
        <LiveStandingsTable isLoading={standingsLoading} data={standings} managedClubId={managedClubId} />
      )}
    </Box>
  )
}
