import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MatchActionsDialog } from './MatchActionsDialog'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'

function renderTacticsTab() {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={1}
      onPitch={[]}
      bench={[]}
      subsRemaining={3}
      pending={false}
      onApply={() => {}}
      currentTactics={{ mentality: 'BALANCED', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }}
    />,
  )
}

describe('MatchActionsDialog tactics tab', () => {
  it('offers every formation from the shared catalogue', () => {
    renderTacticsTab()
    const formation = screen.getByLabelText('Formação') as HTMLSelectElement
    expect(Array.from(formation.options).map(o => o.value)).toEqual(FORMATION_CODES)
    expect(FORMATION_CODES).toHaveLength(16)
  })

  it('labels tactic options with the shared friendly names', () => {
    renderTacticsTab()
    const mentality = screen.getByLabelText('Mentalidade') as HTMLSelectElement
    expect(Array.from(mentality.options).map(o => o.text)).toEqual(Object.values(MENTALITY_LABEL))
    const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
    expect(Array.from(pressing.options).map(o => o.text)).toEqual(Object.values(PRESSING_LABEL))
    const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
    expect(Array.from(marking.options).map(o => o.text)).toEqual(Object.values(MARKING_LABEL))
  })
})
