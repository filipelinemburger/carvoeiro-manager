import { Tab, Tabs } from '@mui/material'
import type { Phase } from '../../api/types'

type Props = {
  phases: Phase[]
  selectedPhaseId: string
  onSelect: (phaseId: string) => void
}

/** One tab per phase, in bracket (ordinal) order; the tab label is the pt-BR display name. */
export function PhaseSelector({ phases, selectedPhaseId, onSelect }: Props) {
  const orderedPhases = [...phases].sort((a, b) => a.ordinal - b.ordinal)

  return (
    <Tabs
      value={selectedPhaseId}
      onChange={(_, value: string) => onSelect(value)}
      variant="scrollable"
      scrollButtons="auto"
      sx={{ minHeight: 40 }}
    >
      {orderedPhases.map(phase => (
        <Tab key={phase.id} value={phase.id} label={phase.name} sx={{ minHeight: 40 }} />
      ))}
    </Tabs>
  )
}
