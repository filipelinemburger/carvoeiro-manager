import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { TrainingBulkCard } from './TrainingBulkCard'
import type { BulkTrainingRule } from './bulkTraining'

function renderCard(rules: BulkTrainingRule[] = []) {
  const onRulesChange = vi.fn()
  const onApply = vi.fn()
  render(<TrainingBulkCard rules={rules} onRulesChange={onRulesChange} onApply={onApply} />)
  return { onRulesChange, onApply }
}

function expand() {
  fireEvent.click(screen.getByRole('button', { name: 'Treino por posição' }))
}

describe('TrainingBulkCard', () => {
  it('starts collapsed', () => {
    renderCard()
    // The Collapse uses unmountOnExit, so a collapsed card has no controls in the DOM at all.
    expect(screen.queryByRole('button', { name: 'Adicionar posição' })).toBeNull()
  })

  it('adds a rule defaulted to the first unused position', () => {
    const { onRulesChange } = renderCard()
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Adicionar posição' }))
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
    ])
  })

  it('offers only unused positions to a second rule', () => {
    renderCard([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
    expand()
    const second = screen.getByRole('combobox', { name: 'Posição da regra 2' })
    const options = Array.from(second.querySelectorAll('option')).map(o => o.getAttribute('value'))
    // Its own position stays selectable; the other rule's does not.
    expect(options).toContain('ZAGUEIRO')
    expect(options).not.toContain('GOLEIRO')
  })

  it('edits a rule in place', () => {
    const { onRulesChange } = renderCard([{ position: 'GOLEIRO', focus: null, intensity: 'NORMAL' }])
    expand()
    fireEvent.change(screen.getByRole('combobox', { name: 'Foco da regra 1' }), {
      target: { value: 'REFLEXOS' },
    })
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'NORMAL' },
    ])
  })

  it('removes a rule', () => {
    const { onRulesChange } = renderCard([
      { position: 'GOLEIRO', focus: null, intensity: 'NORMAL' },
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Remover regra 1' }))
    expect(onRulesChange).toHaveBeenCalledWith([
      { position: 'ZAGUEIRO', focus: null, intensity: 'NORMAL' },
    ])
  })

  it('disables Salvar with no rules and enables it with one', () => {
    const { unmount } = render(
      <TrainingBulkCard rules={[]} onRulesChange={vi.fn()} onApply={vi.fn()} />,
    )
    expand()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled()
    unmount()
    renderCard([{ position: 'GOLEIRO', focus: null, intensity: 'NORMAL' }])
    expand()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeEnabled()
  })

  it('calls onApply when Salvar is clicked', () => {
    const { onApply } = renderCard([{ position: 'GOLEIRO', focus: 'REFLEXOS', intensity: 'INTENSA' }])
    expand()
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(onApply).toHaveBeenCalledTimes(1)
  })
})
