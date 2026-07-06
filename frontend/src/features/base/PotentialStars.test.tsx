import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { PotentialStars } from './PotentialStars'

describe('PotentialStars', () => {
  it('labels the rendered star count', () => {
    render(<PotentialStars stars={4} />)
    expect(screen.getByLabelText('Potencial 4 de 5')).toBeInTheDocument()
  })

  it('clamps values above 5', () => {
    render(<PotentialStars stars={7} />)
    expect(screen.getByLabelText('Potencial 5 de 5')).toBeInTheDocument()
  })

  it('clamps negative values to 0', () => {
    render(<PotentialStars stars={-2} />)
    expect(screen.getByLabelText('Potencial 0 de 5')).toBeInTheDocument()
  })
})
