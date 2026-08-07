import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { KnockoutBracketList } from './KnockoutBracketList'
import type { KnockoutTie } from '../../api/types'

const tie: KnockoutTie = {
  tieId: 'tie-1',
  tieOrder: 1,
  higherSeedClubId: 'club-1',
  higherSeedClubName: 'Criciúma',
  lowerSeedClubId: 'club-2',
  lowerSeedClubName: 'Avaí',
  // Higher seed is away in leg 1, home in leg 2: higher aggregate = 1 (away leg1) + 2 (home leg2) = 3
  // Lower aggregate = 1 (home leg1) + 1 (away leg2) = 2
  firstLegHomeGoals: 1,
  firstLegAwayGoals: 1,
  secondLegHomeGoals: 2,
  secondLegAwayGoals: 1,
  penaltyHome: null,
  penaltyAway: null,
  winnerClubId: 'club-1',
}

const penaltyTie: KnockoutTie = {
  tieId: 'tie-2',
  tieOrder: 1,
  higherSeedClubId: 'club-3',
  higherSeedClubName: 'Figueirense',
  lowerSeedClubId: 'club-4',
  lowerSeedClubName: 'Chapecoense',
  firstLegHomeGoals: 0,
  firstLegAwayGoals: 1,
  secondLegHomeGoals: 1,
  secondLegAwayGoals: 2,
  penaltyHome: 5,
  penaltyAway: 3,
  winnerClubId: 'club-3',
}

const unplayedTie: KnockoutTie = {
  tieId: 'tie-3',
  tieOrder: 1,
  higherSeedClubId: 'club-5',
  higherSeedClubName: 'Joinville',
  lowerSeedClubId: 'club-6',
  lowerSeedClubName: 'Brusque',
  firstLegHomeGoals: null,
  firstLegAwayGoals: null,
  secondLegHomeGoals: null,
  secondLegAwayGoals: null,
  penaltyHome: null,
  penaltyAway: null,
  winnerClubId: null,
}

describe('KnockoutBracketList', () => {
  it('renders each tie with both legs and the aggregate', () => {
    render(<KnockoutBracketList phaseName="Quartas de final" ties={[tie]} managedClubId="club-1" />)

    expect(screen.getByText('Quartas de final')).toBeInTheDocument()
    expect(screen.getByText('Criciúma')).toBeInTheDocument()
    expect(screen.getByText('Avaí')).toBeInTheDocument()
    expect(screen.getByText('3 x 2')).toBeInTheDocument() // aggregate
  })

  it('shows the shootout score when a tie went to penalties', () => {
    render(<KnockoutBracketList phaseName="Final" ties={[penaltyTie]} managedClubId="club-1" />)

    expect(screen.getByText(/5 x 3 nos pênaltis/)).toBeInTheDocument()
  })

  it('renders an unplayed tie without scores and without crashing', () => {
    render(<KnockoutBracketList phaseName="Semifinal" ties={[unplayedTie]} managedClubId="club-1" />)

    expect(screen.getByText('Semifinal')).toBeInTheDocument()
    expect(screen.queryByText('x')).not.toBeInTheDocument()
  })

  it('highlights the managed club', () => {
    render(<KnockoutBracketList phaseName="Final" ties={[tie]} managedClubId="club-1" />)

    expect(screen.getByTestId('tie-club-club-1')).toHaveAttribute('data-managed', 'true')
  })
})
