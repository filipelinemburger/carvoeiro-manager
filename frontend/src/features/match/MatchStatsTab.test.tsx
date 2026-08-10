import { render, screen, within } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { MatchStatsTab } from './MatchStatsTab'
import type { LiveKitColors, MatchStats } from '../../api/types'

const STATS: MatchStats = {
  home: {
    possession: 55, shots: 13, shotsOnTarget: 6, saves: 4, corners: 7,
    offsides: 2, fouls: 9, yellowCards: 3, redCards: 1,
  },
  away: {
    possession: 45, shots: 12, shotsOnTarget: 5, saves: 3, corners: 5,
    offsides: 1, fouls: 8, yellowCards: 2, redCards: 0,
  },
}

const HOME_KIT: LiveKitColors = { primary: '#003399', secondary: '#ffffff' }
const AWAY_KIT: LiveKitColors = { primary: '#ffcc00', secondary: '#000000' }

const ROW_LABELS: [string, string][] = [
  ['possession', 'Posse de bola'],
  ['shots', 'Finalizações'],
  ['shotsOnTarget', 'Finalizações no gol'],
  ['saves', 'Defesas'],
  ['corners', 'Escanteios'],
  ['offsides', 'Impedimentos'],
  ['fouls', 'Faltas'],
  ['yellowCards', 'Cartões amarelos'],
  ['redCards', 'Cartões vermelhos'],
]

describe('MatchStatsTab', () => {
  it('renders all nine rows with their PT-BR labels and the club short names in the header', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    expect(screen.getByText('CRI')).toBeInTheDocument()
    expect(screen.getByText('RIV')).toBeInTheDocument()

    ROW_LABELS.forEach(([key, label]) => {
      expect(within(screen.getByTestId(`stat-${key}`)).getByText(label)).toBeInTheDocument()
    })
  })

  it('renders the nine rows in the specified order', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    // Row testids are `stat-<key>` with no further dashes; bar-segment testids
    // (`stat-<key>-bar-home`/`-bar-away`) contain extra dashes, so this regex excludes them.
    const testIds = screen.getAllByTestId(/^stat-[A-Za-z]+$/).map(el => el.getAttribute('data-testid'))
    expect(testIds).toEqual(ROW_LABELS.map(([key]) => `stat-${key}`))
  })

  it('renders the home value on the left and the away value on the right within one row', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    const row = screen.getByTestId('stat-shots')
    // Both the home (13) and away (12) shot counts must be present, in DOM order home-then-away —
    // asserting only presence (not order) would not catch a swapped home/away data mapping.
    const numbers = within(row).getAllByText(/^\d+$/)
    expect(numbers.map(el => el.textContent)).toEqual(['13', '12'])
  })

  it('renders possession with a % suffix and the other rows without one', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    expect(within(screen.getByTestId('stat-possession')).getByText('55%')).toBeInTheDocument()
    expect(within(screen.getByTestId('stat-possession')).getByText('45%')).toBeInTheDocument()
    expect(within(screen.getByTestId('stat-shots')).queryByText(/%/)).not.toBeInTheDocument()
    expect(within(screen.getByTestId('stat-fouls')).queryByText(/%/)).not.toBeInTheDocument()
  })

  it('renders a 50/50 bar with no NaN anywhere when a stat is 0-0', () => {
    const zeroStats: MatchStats = {
      home: { ...STATS.home, shots: 0 },
      away: { ...STATS.away, shots: 0 },
    }
    render(<MatchStatsTab stats={zeroStats} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    // sx compiles width into an emotion-generated class, never an inline style attribute, so this
    // must resolve the computed/class-based style (toHaveStyle) rather than read el.style.width.
    expect(screen.getByTestId('stat-shots-bar-home')).toHaveStyle({ width: '50%' })
    expect(screen.getByTestId('stat-shots-bar-away')).toHaveStyle({ width: '50%' })
    expect(document.documentElement.innerHTML).not.toContain('NaN')
  })

  it('renders zeroes instead of throwing when stats is null', () => {
    expect(() => render(
      <MatchStatsTab stats={null} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />,
    )).not.toThrow()

    expect(within(screen.getByTestId('stat-shots')).getAllByText('0')).toHaveLength(2)
    expect(within(screen.getByTestId('stat-fouls')).getAllByText('0')).toHaveLength(2)
    expect(within(screen.getByTestId('stat-possession')).getAllByText('50%')).toHaveLength(2)
    expect(screen.getByTestId('stat-shots-bar-home')).toHaveStyle({ width: '50%' })
    expect(document.documentElement.innerHTML).not.toContain('NaN')
  })

  it('falls back to the default kit colours with no NaN when both kits are null', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={null} awayKit={null} />)

    // Same fallback kit on both sides -> tokenColors resolves to a fixed, known fill, never
    // `rgb(NaN, NaN, NaN)` — see clubKit.ts / clubKit.test.ts for the same fallback value.
    expect(screen.getByTestId('stat-shots-bar-home')).toHaveStyle({ backgroundColor: 'rgb(91, 102, 112)' })
    expect(screen.getByTestId('stat-shots-bar-away')).toHaveStyle({ backgroundColor: 'rgb(91, 102, 112)' })
    expect(document.documentElement.innerHTML).not.toContain('NaN')
  })

  it('draws a 1px divider between the bar segments when the two kits clash', () => {
    const clashAway: LiveKitColors = { primary: '#002266', secondary: '#ffffff' }
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={clashAway} />)

    const homeBar = screen.getByTestId('stat-shots-bar-home')
    expect(homeBar).toHaveStyle({ borderRightWidth: '1px', borderRightColor: 'rgba(0, 0, 0, 0.12)' })
  })

  it('draws no divider between the bar segments when the two kits do not clash', () => {
    render(<MatchStatsTab stats={STATS} homeShort="CRI" awayShort="RIV" homeKit={HOME_KIT} awayKit={AWAY_KIT} />)

    const homeBar = screen.getByTestId('stat-shots-bar-home')
    expect(homeBar).toHaveStyle({ borderStyle: 'none' })
  })
})
