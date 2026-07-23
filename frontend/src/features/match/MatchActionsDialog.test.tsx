import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MatchActionsDialog } from './MatchActionsDialog'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, GAME_STYLE_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'

function renderTacticsTab(onApply: (payload: unknown) => void = () => {}) {
  render(
    <MatchActionsDialog
      open
      onClose={() => {}}
      initialTab={1}
      onPitch={[]}
      bench={[]}
      subsRemaining={3}
      pending={false}
      onApply={onApply}
      currentTactics={{ mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' }}
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
    const gameStyle = screen.getByLabelText('Estilo de jogo') as HTMLSelectElement
    expect(Array.from(gameStyle.options).map(o => o.text)).toEqual(Object.values(GAME_STYLE_LABEL))
    const pressing = screen.getByLabelText('Pressão') as HTMLSelectElement
    expect(Array.from(pressing.options).map(o => o.text)).toEqual(Object.values(PRESSING_LABEL))
    const marking = screen.getByLabelText('Marcação') as HTMLSelectElement
    expect(Array.from(marking.options).map(o => o.text)).toEqual(Object.values(MARKING_LABEL))
  })

  it('treats a game-style-only change as a tactics change and includes gameStyle in the applied payload', () => {
    const onApply = vi.fn()
    renderTacticsTab(onApply)

    fireEvent.change(screen.getByLabelText('Estilo de jogo'), { target: { value: 'RETRANCA' } })
    fireEvent.click(screen.getByRole('button', { name: /continuar/i }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({
      subs: [],
      tactics: expect.objectContaining({ gameStyle: 'RETRANCA', mentality: 'BALANCED' }),
    })
  })
})
