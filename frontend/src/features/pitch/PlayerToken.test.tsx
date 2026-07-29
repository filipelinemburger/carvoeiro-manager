import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PlayerToken } from './PlayerToken'

describe('PlayerToken', () => {
  it('renders the number and surname', () => {
    render(<PlayerToken number={9} surname="Gamalho" fill="#f7d417" text="#111111" fitness={80} />)
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('Gamalho')).toBeInTheDocument()
  })

  it('paints the kit fill colour on the token', () => {
    render(<PlayerToken number={1} fill="#003399" text="#ffffff" fitness={90} />)
    const token = screen.getByTestId('player-token')
    // jsdom normalizes hex to rgb
    expect(token.querySelector('[data-role="disc"]')).toHaveStyle({ backgroundColor: 'rgb(0, 51, 153)' })
  })

  it('shows a goal badge when scored', () => {
    render(<PlayerToken number={9} fill="#f7d417" text="#111111" fitness={70} badges={{ goal: true }} />)
    expect(screen.getByLabelText('Gol')).toBeInTheDocument()
  })

  it('shows a card badge when booked', () => {
    render(<PlayerToken number={8} fill="#f7d417" text="#111111" fitness={70} badges={{ card: 'YELLOW' }} />)
    expect(screen.getByLabelText('Cartão amarelo')).toBeInTheDocument()
  })

  it('names the improvised player\'s real positions in the warn tooltip', () => {
    render(<PlayerToken number={4} fill="#f7d417" text="#111111" fitness={80} posLabel="LE"
      outOfPosition naturalPositions="ZAG · VOL" />)
    const token = screen.getByTestId('player-token')
    expect(token).toHaveAttribute('data-out-of-position', 'true')
    expect(token).toHaveAttribute('title', 'Improvisado — posição natural: ZAG · VOL')
  })

  it('falls back to the bare warning when the player carries no detailed positions', () => {
    render(<PlayerToken number={4} fill="#f7d417" text="#111111" fitness={80} posLabel="ZAG" outOfPosition />)
    const token = screen.getByTestId('player-token')
    expect(token).toHaveAttribute('data-out-of-position', 'true')
    expect(token).toHaveAttribute('title', 'Improvisado')
  })

  it('renders normally (no warn cue, shows the slot label) when in position', () => {
    render(<PlayerToken number={4} fill="#f7d417" text="#111111" fitness={80} posLabel="ZAG" />)
    const token = screen.getByTestId('player-token')
    expect(token).not.toHaveAttribute('data-out-of-position')
    expect(token).toHaveAttribute('title', 'ZAG')
  })
})
