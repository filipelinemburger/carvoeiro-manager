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
    // sx compiles bgcolor/color into an emotion-generated class, never an inline style attribute,
    // so this must resolve the computed/class-based style (toHaveStyle) rather than read
    // card.style.* — the fallback kit resolves to a fixed, known bg/fg pair, not just "no NaN".
    expect(card).toHaveStyle({ backgroundColor: 'rgb(91, 102, 112)', color: 'rgb(255, 255, 255)' })
  })

  it('falls back when the primary colour is a non-hex CSS keyword', () => {
    render(
      <ClubCard shortName="Criciúma" colors={{ primary: 'red', secondary: '#12' }} isOwn onClick={() => {}} />,
    )
    const card = screen.getByTestId('club-card-own')
    // Both primary ('red') and secondary ('#12') are non-hex, so both fall back to the default
    // kit — same resolved colours as the null-colors case above.
    expect(card).toHaveStyle({ backgroundColor: 'rgb(91, 102, 112)', color: 'rgb(255, 255, 255)' })
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

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn()
    render(
      <ClubCard
        shortName="Criciúma"
        colors={{ primary: '#f7d417', secondary: '#111111' }}
        isOwn
        onClick={onClick}
        disabled
      />,
    )
    const button = screen.getByTestId('club-card-own')
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('fills its share of the scoreboard row instead of hugging its label', () => {
    render(
      <ClubCard
        shortName="Criciúma"
        colors={{ primary: '#f7d417', secondary: '#111111' }}
        isOwn
        onClick={() => {}}
      />,
    )
    const card = screen.getByTestId('club-card-own')
    // Same sx-vs-inline-style caveat as the colour assertions above: this must go through the
    // computed style. `flex: 1` resolves to grow 1 / basis 0%, and the old `minWidth: 100` (which
    // made the card hug its label and sit as a pill at the row's edge) must be gone — a card that
    // grows but keeps a 100px floor still can't shrink below it on a narrow screen.
    expect(card).toHaveStyle({ flexGrow: '1', flexBasis: '0%', minWidth: '0px' })
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
    // Same reasoning as above: card.style.border reads the inline style attribute, which sx
    // never populates, so it would read '' even if a border were always rendered. Assert the
    // computed border instead.
    expect(card).toHaveStyle({ borderStyle: 'none' })
  })
})
