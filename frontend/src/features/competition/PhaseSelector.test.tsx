import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PhaseSelector } from './PhaseSelector'
import type { Phase } from '../../api/types'

const phase = (ordinal: number, name: string, kind: Phase['kind'], complete: boolean): Phase => ({
  id: `phase-${ordinal}`,
  ordinal,
  name,
  kind,
  legs: kind === 'KNOCKOUT' ? 2 : 1,
  rounds: kind === 'KNOCKOUT' ? 2 : 11,
  qualifiers: kind === 'ROUND_ROBIN' ? 8 : null,
  complete,
})

// Deliberately out of order: the component must sort, not rely on its input.
const PHASES: Phase[] = [
  phase(2, 'Semifinal', 'KNOCKOUT', false),
  phase(0, 'Primeira Fase', 'ROUND_ROBIN', true),
  phase(3, 'Final', 'KNOCKOUT', false),
  phase(1, 'Quartas de final', 'KNOCKOUT', true),
]

describe('PhaseSelector', () => {
  it('renders one tab per phase in bracket order regardless of input order', () => {
    render(<PhaseSelector phases={PHASES} selectedPhaseId="phase-0" onSelect={() => {}} />)

    const labels = screen.getAllByRole('tab').map(t => t.textContent)
    expect(labels).toEqual(['Primeira Fase', 'Quartas de final', 'Semifinal', 'Final'])
  })

  it('labels each tab with the pt-BR display name, never the enum kind', () => {
    render(<PhaseSelector phases={PHASES} selectedPhaseId="phase-0" onSelect={() => {}} />)

    // The wire carries kind = 'ROUND_ROBIN' | 'KNOCKOUT'; showing that to a player would be the
    // same label/enum confusion that has bitten this app before.
    expect(screen.queryByText('ROUND_ROBIN')).not.toBeInTheDocument()
    expect(screen.queryByText('KNOCKOUT')).not.toBeInTheDocument()
    expect(screen.getByText('Quartas de final')).toBeInTheDocument()
  })

  it('marks the selected phase as the active tab', () => {
    render(<PhaseSelector phases={PHASES} selectedPhaseId="phase-2" onSelect={() => {}} />)

    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('Semifinal')
  })

  it('reports the chosen phase id when a tab is clicked', async () => {
    const onSelect = vi.fn()
    render(<PhaseSelector phases={PHASES} selectedPhaseId="phase-0" onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('tab', { name: 'Final' }))

    expect(onSelect).toHaveBeenCalledWith('phase-3')
  })

  it('renders nothing but an empty tablist for a competition with no phases', () => {
    render(<PhaseSelector phases={[]} selectedPhaseId="" onSelect={() => {}} />)

    expect(screen.queryAllByRole('tab')).toHaveLength(0)
  })
})
