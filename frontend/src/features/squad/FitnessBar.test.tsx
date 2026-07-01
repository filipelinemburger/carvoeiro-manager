// FitnessBar.test.tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { FitnessBar } from './FitnessBar'

describe('FitnessBar', () => {
  it('shows the percentage and an accessible label', () => {
    render(<FitnessBar fitness={73} />)
    expect(screen.getByText('73%')).toBeInTheDocument()
    expect(screen.getByLabelText('Condição 73%')).toBeInTheDocument()
  })

  it('uses the danger color when fitness is very low', () => {
    render(<FitnessBar fitness={25} />)
    const bar = screen.getByLabelText('Condição 25%')
    expect(bar.getAttribute('data-severity')).toBe('error')
  })

  it('uses the success color when fully fit', () => {
    render(<FitnessBar fitness={95} />)
    expect(screen.getByLabelText('Condição 95%').getAttribute('data-severity')).toBe('success')
  })
})
