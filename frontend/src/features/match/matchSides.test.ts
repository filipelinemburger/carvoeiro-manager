import { describe, it, expect } from 'vitest'
import { sidesOf } from './matchSides'
import type { LiveMatchState, LivePlayer, PlayerRating } from '../../api/types'

const managedPlayer: LivePlayer = {
  playerId: 'm1', name: 'Meu Jogador', position: 'ATT', overall: 78, fitness: 90,
  hobbled: false, onPitch: true, used: true, eligible: true, role: 'ATT',
}

const opponentPlayer: LivePlayer = {
  playerId: 'o1', name: 'Jogador Rival', position: 'DEF', overall: 70, fitness: 85,
  hobbled: false, onPitch: true, used: true, eligible: true, role: 'DEF',
}

const managedRating: PlayerRating = {
  playerId: 'm1', name: 'Meu Jogador', position: 'ATT', minutesPlayed: 90, goals: 1, rating: 7.5,
}

const opponentRating: PlayerRating = {
  playerId: 'o1', name: 'Jogador Rival', position: 'DEF', minutesPlayed: 90, goals: 0, rating: 6.2,
}

// Zero-filled stats: exactly what minute 0 looks like.
const ZERO_STATS = {
  possession: 50, shots: 0, shotsOnTarget: 0, saves: 0, corners: 0, offsides: 0,
  fouls: 0, yellowCards: 0, redCards: 0,
}

function baseState(overrides: Partial<LiveMatchState>): LiveMatchState {
  return {
    fixtureId: 'f1', competitionName: 'Catarinense', round: 0, matchDate: '2026-03-01',
    stadiumName: 'Estádio', homeClubId: 'club-casa', homeClubName: 'Casa FC', homeClubShortName: 'Casa',
    awayClubId: 'club-fora', awayClubName: 'Fora FC', awayClubShortName: 'Fora', managedIsHome: true,
    minute: 12, homeGoals: 0, awayGoals: 0, status: 'IN_PROGRESS',
    events: [],
    onPitch: [managedPlayer], bench: [], subsRemaining: 5,
    currentTactics: { mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-3-3' },
    playerRatings: [managedRating],
    opponentOnPitch: [opponentPlayer],
    opponentTactics: { mentality: 'BALANCED', gameStyle: 'EQUILIBRADO', attackZone: 'MISTO', pressing: 'MEDIUM', marking: 'ZONAL', formation: '4-4-2' },
    opponentRatings: [opponentRating],
    homeClubColors: { primary: '#f7d417', secondary: '#111111' },
    awayClubColors: { primary: '#003399', secondary: '#ffffff' },
    competitionId: 'comp-1', parallelScores: [],
    weather: null, temperatureC: null, attendance: null,
    stats: { home: ZERO_STATS, away: ZERO_STATS },
    ...overrides,
  }
}

describe('sidesOf', () => {
  it('does not invert the managed/opponent mapping when the managed club plays home', () => {
    const state = baseState({ managedIsHome: true })

    const { home, away } = sidesOf(state)

    expect(home.shortName).toBe('Casa')
    expect(home.formation).toBe('4-3-3')
    expect(home.players).toEqual([managedPlayer])
    expect(home.ratings).toEqual([managedRating])
    expect(home.isOwnSide).toBe(true)

    expect(away.shortName).toBe('Fora')
    expect(away.formation).toBe('4-4-2')
    expect(away.players).toEqual([opponentPlayer])
    expect(away.ratings).toEqual([opponentRating])
    expect(away.isOwnSide).toBe(false)
  })

  it('does not invert the managed/opponent mapping when the managed club plays away', () => {
    const state = baseState({ managedIsHome: false })

    const { home, away } = sidesOf(state)

    // The managed player (onPitch) must land on the AWAY side, since the club plays away.
    expect(home.shortName).toBe('Casa')
    expect(home.players).toEqual([opponentPlayer])
    expect(home.ratings).toEqual([opponentRating])
    expect(home.formation).toBe('4-4-2')
    expect(home.isOwnSide).toBe(false)

    expect(away.shortName).toBe('Fora')
    expect(away.players).toEqual([managedPlayer])
    expect(away.ratings).toEqual([managedRating])
    expect(away.formation).toBe('4-3-3')
    expect(away.isOwnSide).toBe(true)
  })

  it('leaves the kit colours home/away oriented, unflipped by managedIsHome', () => {
    const homeState = sidesOf(baseState({ managedIsHome: true }))
    const awayState = sidesOf(baseState({ managedIsHome: false }))

    // Same homeClubColors/awayClubColors in both fixtures -> kit assignment must be identical
    // regardless of which side the manager is on.
    expect(homeState.home.kit).toEqual({ primary: '#f7d417', secondary: '#111111' })
    expect(homeState.away.kit).toEqual({ primary: '#003399', secondary: '#ffffff' })
    expect(awayState.home.kit).toEqual({ primary: '#f7d417', secondary: '#111111' })
    expect(awayState.away.kit).toEqual({ primary: '#003399', secondary: '#ffffff' })
  })
})
