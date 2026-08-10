import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { ClubCard } from './ClubCard'

describe('ClubCard', () => {
  it('yields dark text for a light primary colour (through tokenColors)', () => {
    render(
      <ClubCard
        shortName="Criciúma"
        colors={{ primary: '#f7d417', secondary: '#f7d417' }}
        isOwn
        onClick={() => {}}
      />,
    )
    const card = screen.getByTestId('club-card-own')
    expect(card).toHaveStyle({ color: 'rgb(0, 0, 0)' })
  })

  it('yields light text for a dark primary colour (through tokenColors)', () => {
    render(
      <ClubCard
        shortName="Rival"
        colors={{ primary: '#111111', secondary: '#111111' }}
        isOwn={false}
        onClick={() => {}}
      />,
    )
    const card = screen.getByTestId('club-card-opponent')
    expect(card).toHaveStyle({ color: 'rgb(255, 255, 255)' })
  })

  it('falls back with no NaN anywhere in the rendered style when colors is null', () => {
    render(<ClubCard shortName="Criciúma" colors={null} isOwn onClick={() => {}} />)
    const card = screen.getByTestId('club-card-own')
    expect(card.getAttribute('style') ?? '').not.toContain('NaN')
    expect(card.style.backgroundColor).not.toContain('NaN')
    expect(card.style.color).not.toContain('NaN')
  })

  it('falls back when the primary colour is a non-hex CSS keyword', () => {
    render(
      <ClubCard shortName="Criciúma" colors={{ primary: 'red', secondary: '#12' }} isOwn onClick={() => {}} />,
    )
    const card = screen.getByTestId('club-card-own')
    expect(card.getAttribute('style') ?? '').not.toContain('NaN')
  })

  it('renders the short name and the caption', () => {
    render(
      <ClubCard
        shortName="Criciúma"
        colors={{ primary: '#f7d417', secondary: '#111111' }}
        caption="Substituições (5)"
        isOwn
        onClick={() => {}}
      />,
    )
    expect(screen.getByText('Criciúma')).toBeInTheDocument()
    expect(screen.getByText('Substituições (5)')).toBeInTheDocument()
  })

  it('fires onClick when clicked, and is reachable by its aria-label and data-testid', () => {
    const onClick = vi.fn()
    render(
      <ClubCard
        shortName="Criciúma"
        colors={{ primary: '#f7d417', secondary: '#111111' }}
        isOwn
        onClick={onClick}
      />,
    )
    const button = screen.getByRole('button', { name: 'Ações do meu time — Criciúma' })
    expect(button).toHaveAttribute('data-testid', 'club-card-own')
    fireEvent.click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('uses the opponent aria-label and testid when isOwn is false', () => {
    const onClick = vi.fn()
    render(
      <ClubCard
        shortName="Rival"
        colors={{ primary: '#003399', secondary: '#ffffff' }}
        isOwn={false}
        onClick={onClick}
      />,
    )
    const button = screen.getByRole('button', { name: 'Escalação do adversário — Rival' })
    expect(button).toHaveAttribute('data-testid', 'club-card-opponent')
    fireEvent.click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('gives the card a 1px solid outline in its own fg colour when clash is true', () => {
    render(
      <ClubCard
        shortName="Rival"
        colors={{ primary: '#003399', secondary: '#ffffff' }}
        isOwn={false}
        onClick={() => {}}
        clash
      />,
    )
    const card = screen.getByTestId('club-card-opponent')
    expect(card).toHaveStyle({ border: '1px solid rgb(255, 255, 255)' })
  })

  it('renders no outline when clash is false or omitted', () => {
    render(
      <ClubCard
        shortName="Rival"
        colors={{ primary: '#003399', secondary: '#ffffff' }}
        isOwn={false}
        onClick={() => {}}
      />,
    )
    const card = screen.getByTestId('club-card-opponent')
    expect(card.style.border).toBe('')
  })
})
