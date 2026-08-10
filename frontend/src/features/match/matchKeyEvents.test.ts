import { describe, it, expect } from 'vitest'
import { summarizeKeyEvents } from './matchKeyEvents'
import type { LiveEvent } from '../../api/types'

// Minimal builder so each test only states the fields it cares about.
function event(overrides: Partial<LiveEvent> & Pick<LiveEvent, 'minute' | 'type' | 'home'>): LiveEvent {
  return {
    playerId: 'p1', playerName: 'Jogador', playerInId: null, playerInName: null,
    severity: null, text: '',
    ...overrides,
  }
}

describe('summarizeKeyEvents', () => {
  it('groups goals by scorer in insertion order, keeping each scorer\'s minutes chronological', () => {
    const events: LiveEvent[] = [
      event({ minute: 10, type: 'GOAL', home: true, playerName: 'Camisa 9' }),
      event({ minute: 25, type: 'PENALTY_GOAL', home: true, playerName: 'Meia 10' }),
      event({ minute: 40, type: 'FREE_KICK_GOAL', home: true, playerName: 'Camisa 9' }),
    ]

    const { home } = summarizeKeyEvents(events)

    expect(home.scorers).toEqual([
      { name: 'Camisa 9', minutes: [10, 40] },
      { name: 'Meia 10', minutes: [25] },
    ])
  })

  it('routes each event to home or away based on e.home', () => {
    const events: LiveEvent[] = [
      event({ minute: 5, type: 'GOAL', home: true, playerName: 'Casa' }),
      event({ minute: 15, type: 'GOAL', home: false, playerName: 'Visitante' }),
    ]

    const { home, away } = summarizeKeyEvents(events)

    expect(home.scorers).toEqual([{ name: 'Casa', minutes: [5] }])
    expect(away.scorers).toEqual([{ name: 'Visitante', minutes: [15] }])
  })

  it('appends discipline events chronologically without grouping by name', () => {
    const events: LiveEvent[] = [
      event({ minute: 30, type: 'YELLOW_CARD', home: true, playerName: 'Zagueiro' }),
      event({ minute: 60, type: 'INJURY', home: true, playerName: 'Lateral' }),
      event({ minute: 75, type: 'PENALTY_MISSED', home: true, playerName: 'Atacante' }),
    ]

    const { home } = summarizeKeyEvents(events)

    expect(home.discipline).toEqual([
      { name: 'Zagueiro', minute: 30, kind: 'YELLOW' },
      { name: 'Lateral', minute: 60, kind: 'INJURY' },
      { name: 'Atacante', minute: 75, kind: 'PENALTY_MISSED' },
    ])
  })

  it('ignores CHANCE, SUBSTITUTION, TACTICS_CHANGE and HALF_TIME', () => {
    const events: LiveEvent[] = [
      event({ minute: 20, type: 'CHANCE', home: true }),
      event({ minute: 46, type: 'SUBSTITUTION', home: true }),
      event({ minute: 46, type: 'TACTICS_CHANGE', home: true }),
      event({ minute: 45, type: 'HALF_TIME', home: true }),
    ]

    const result = summarizeKeyEvents(events)

    expect(result).toEqual({
      home: { scorers: [], discipline: [] },
      away: { scorers: [], discipline: [] },
    })
  })

  // Nuance 1: CORNER_GOAL carries the corner taker in playerInName and the scorer in
  // playerName. Crediting the wrong field would put the goal on the taker, not the scorer.
  it('credits a CORNER_GOAL to playerName (the scorer), not playerInName (the corner taker)', () => {
    const events: LiveEvent[] = [
      event({
        minute: 33, type: 'CORNER_GOAL', home: true,
        playerName: 'Zagueiro Cabeceador', playerInName: 'Cobrador de Escanteio',
      }),
    ]

    const { home } = summarizeKeyEvents(events)

    expect(home.scorers).toEqual([{ name: 'Zagueiro Cabeceador', minutes: [33] }])
  })

  // Nuance 2: a second yellow makes the backend emit BOTH a YELLOW_CARD and a RED_CARD for the
  // same player at the same minute. Both discipline lines must survive.
  it('emits both a YELLOW_CARD and a RED_CARD line for a second-yellow dismissal', () => {
    const events: LiveEvent[] = [
      event({ minute: 70, type: 'YELLOW_CARD', home: false, playerName: 'Volante' }),
      event({ minute: 70, type: 'RED_CARD', home: false, playerName: 'Volante' }),
    ]

    const { away } = summarizeKeyEvents(events)

    expect(away.discipline).toEqual([
      { name: 'Volante', minute: 70, kind: 'YELLOW' },
      { name: 'Volante', minute: 70, kind: 'RED' },
    ])
  })

  // Nuance 3: own goals do not exist in this engine, so e.home is always the scoring side —
  // there is no inversion to reproduce here. This test just pins the (unremarkable) direct case.
  it('has no own-goal inversion case: e.home is always the scoring side', () => {
    const events: LiveEvent[] = [
      event({ minute: 12, type: 'SET_PIECE_GOAL', home: false, playerName: 'Zagueiro' }),
    ]

    const { home, away } = summarizeKeyEvents(events)

    expect(home.scorers).toEqual([])
    expect(away.scorers).toEqual([{ name: 'Zagueiro', minutes: [12] }])
  })

  it('returns empty sides for an empty event list', () => {
    expect(summarizeKeyEvents([])).toEqual({
      home: { scorers: [], discipline: [] },
      away: { scorers: [], discipline: [] },
    })
  })
})
