import { describe, it, expect } from 'vitest'
import { minutesPlayed, bookingOf, rankByFit, isSuggestedFor, POSITION_ORDER } from './livePlayerContext'
import type { LiveEvent, LivePlayer } from '../../api/types'

// Minimal builder so each test only states the fields it cares about.
function event(overrides: Partial<LiveEvent> & Pick<LiveEvent, 'minute' | 'type' | 'home'>): LiveEvent {
  return {
    playerId: 'p1', playerName: 'Jogador', playerInId: null, playerInName: null,
    severity: null, text: '',
    ...overrides,
  }
}

function player(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Test Player', position: 'GK', overall: 80, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'GOALKEEPER',
    ...overrides,
  }
}

describe('minutesPlayed', () => {
  it('a starter still on at minute 63 has played 63 minutes', () => {
    const events: LiveEvent[] = []
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: true }), events, 63)
    expect(result).toBe(63)
  })

  it('a player who came on at minute 70, with the clock at 78, has played 8 minutes', () => {
    const events: LiveEvent[] = [
      event({ minute: 70, type: 'SUBSTITUTION', home: true, playerInId: 'p1', playerId: 'other1' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: true }), events, 78)
    expect(result).toBe(8)
  })

  it('a starter taken off at minute 55, clock at 80, has played 55 minutes', () => {
    // onPitch: false is the production-shaped value for a player who has already been subbed
    // off (see LiveMatchService.onPitchFor on the backend) — this is the case the fix targets.
    const events: LiveEvent[] = [
      event({ minute: 55, type: 'SUBSTITUTION', home: true, playerId: 'p1', playerInId: 'other1' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: false }), events, 80)
    expect(result).toBe(55)
  })

  it('a substitute who came on at 60 and went off at 75 has played 15 minutes', () => {
    // onPitch: false for realism (the player has since been subbed off); cosmetic here since
    // an entry event is found and the onPitch gate is never reached.
    const events: LiveEvent[] = [
      event({ minute: 60, type: 'SUBSTITUTION', home: true, playerInId: 'p1', playerId: 'other1' }),
      event({ minute: 75, type: 'SUBSTITUTION', home: true, playerId: 'p1', playerInId: 'other2' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: false }), events, 80)
    expect(result).toBe(15)
  })

  it('a SUBSTITUTION for the OTHER side (different ids) does not affect the count', () => {
    const events: LiveEvent[] = [
      event({ minute: 45, type: 'SUBSTITUTION', home: true, playerId: 'other1', playerInId: 'other2' }),
      event({ minute: 70, type: 'SUBSTITUTION', home: true, playerInId: 'p1', playerId: 'other3' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: true }), events, 80)
    expect(result).toBe(10)
  })

  it('never negative', () => {
    const events: LiveEvent[] = [
      event({ minute: 75, type: 'SUBSTITUTION', home: true, playerInId: 'p1', playerId: 'other1' }),
      event({ minute: 70, type: 'SUBSTITUTION', home: true, playerId: 'p1', playerInId: 'other2' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: true }), events, 80)
    expect(result).toBeGreaterThanOrEqual(0)
  })

  it('correctly handles exit event appearing before entry event in the array', () => {
    // Regression test: exit at minute 80 should not be used if it appears before
    // entry at minute 60 in the events array. Ensures the function does not assume
    // chronological array order and does independent scans.
    // onPitch: false for realism (the player has since gone off); cosmetic here since an
    // entry event is found and the onPitch gate is never reached.
    const events: LiveEvent[] = [
      event({ minute: 80, type: 'SUBSTITUTION', home: true, playerId: 'p1', playerInId: 'other1' }),
      event({ minute: 60, type: 'SUBSTITUTION', home: true, playerInId: 'p1', playerId: 'other2' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: false }), events, 90)
    // Entry = 60, Exit = 80, Result = 20
    expect(result).toBe(20)
  })

  it('an unused substitute (never entered, not currently on the pitch) has played 0 minutes', () => {
    // Regression test for the bench-card bug: with no entry event and onPitch false, the old
    // implementation's entry-defaults-to-0 fallback made this return currentMinute (the whole
    // match clock) instead of 0.
    const result = minutesPlayed(player({ playerId: 'bench1', onPitch: false }), [], 57)
    expect(result).toBe(0)
  })

  it('a red-carded player stops accumulating minutes at the dismissal, not the current clock', () => {
    // onPitch: false for realism (a RED_CARD removes the player from the on-pitch list per
    // LiveMatchService.onPitchFor); cosmetic here since an entry event is found and the onPitch
    // gate is never reached.
    const events: LiveEvent[] = [
      event({ minute: 40, type: 'RED_CARD', home: true, playerId: 'p1' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: false }), events, 80)
    expect(result).toBe(40)
  })

  it('a starter sent off (no entry event, onPitch false) still gets their real minutes, not 0', () => {
    // A starter has no SUBSTITUTION entry event. Once sent off, onPitch also flips to false
    // (LiveMatchService.onPitchFor drops the player from the on-pitch list), so the "never
    // played" gate must rely on the exit event (here RED_CARD) to tell this apart from an unused
    // substitute, rather than on onPitch alone. Distinct minute/clock from the case above so this
    // is not a duplicate assertion.
    const events: LiveEvent[] = [
      event({ minute: 25, type: 'RED_CARD', home: true, playerId: 'p1' }),
    ]
    const result = minutesPlayed(player({ playerId: 'p1', onPitch: false }), events, 90)
    expect(result).toBe(25)
  })
})

describe('bookingOf', () => {
  it('a single yellow yields YELLOW', () => {
    const events: LiveEvent[] = [
      event({ minute: 30, type: 'YELLOW_CARD', home: true, playerId: 'p1' }),
    ]
    const result = bookingOf('p1', events)
    expect(result).toBe('YELLOW')
  })

  it('a second yellow (engine emits BOTH YELLOW_CARD and RED_CARD at the same minute for the same player) yields RED, not YELLOW', () => {
    const events: LiveEvent[] = [
      event({ minute: 70, type: 'YELLOW_CARD', home: true, playerId: 'p1' }),
      event({ minute: 70, type: 'RED_CARD', home: true, playerId: 'p1' }),
    ]
    const result = bookingOf('p1', events)
    expect(result).toBe('RED')
  })

  it('a straight red yields RED', () => {
    const events: LiveEvent[] = [
      event({ minute: 45, type: 'RED_CARD', home: true, playerId: 'p1' }),
    ]
    const result = bookingOf('p1', events)
    expect(result).toBe('RED')
  })

  it('no cards yields null', () => {
    const events: LiveEvent[] = []
    const result = bookingOf('p1', events)
    expect(result).toBeNull()
  })

  it('another player\'s card does not leak', () => {
    const events: LiveEvent[] = [
      event({ minute: 30, type: 'YELLOW_CARD', home: true, playerId: 'p2' }),
      event({ minute: 60, type: 'RED_CARD', home: true, playerId: 'p3' }),
    ]
    const result = bookingOf('p1', events)
    expect(result).toBeNull()
  })
})

describe('rankByFit', () => {
  it('a same-position candidate outranks a higher-overall candidate from another position', () => {
    const reference = player({ playerId: 'ref', position: 'GK', overall: 75, role: 'GOALKEEPER' })
    const candidates = [
      player({ playerId: 'c1', position: 'DEF', overall: 95, role: 'DEFENDER' }),
      player({ playerId: 'c2', position: 'GK', overall: 60, role: 'GOALKEEPER' }),
    ]

    const sorted = rankByFit(candidates, reference)

    expect(sorted[0].playerId).toBe('c2')
    expect(sorted[1].playerId).toBe('c1')
  })

  it('among same-position candidates, a same-role one outranks a higher-overall different-role one', () => {
    const reference = player({ playerId: 'ref', position: 'DEF', overall: 75, role: 'STOPPER' })
    const candidates = [
      player({ playerId: 'c1', position: 'DEF', overall: 90, role: 'FULLBACK' }),
      player({ playerId: 'c2', position: 'DEF', overall: 70, role: 'STOPPER' }),
    ]

    const sorted = rankByFit(candidates, reference)

    expect(sorted[0].playerId).toBe('c2')
    expect(sorted[1].playerId).toBe('c1')
  })

  it('among same-position, same-role candidates, higher overall wins', () => {
    const reference = player({ playerId: 'ref', position: 'MID', overall: 75, role: 'BOX_TO_BOX' })
    const candidates = [
      player({ playerId: 'c1', position: 'MID', overall: 70, role: 'BOX_TO_BOX' }),
      player({ playerId: 'c2', position: 'MID', overall: 85, role: 'BOX_TO_BOX' }),
    ]

    const sorted = rankByFit(candidates, reference)

    expect(sorted[0].playerId).toBe('c2')
    expect(sorted[1].playerId).toBe('c1')
  })

  it('an overall tie between two otherwise-equal candidates resolves by id, deterministically', () => {
    const reference = player({ playerId: 'ref', position: 'ATT', overall: 75, role: 'POACHER' })
    const candidates = [
      player({ playerId: 'z_player', position: 'ATT', overall: 75, role: 'POACHER' }),
      player({ playerId: 'a_player', position: 'ATT', overall: 75, role: 'POACHER' }),
    ]

    const sorted = rankByFit(candidates, reference)

    expect(sorted[0].playerId).toBe('a_player')
    expect(sorted[1].playerId).toBe('z_player')
  })

  it('a null reference produces GK → DEF → MID → ATT, overall descending', () => {
    const candidates = [
      player({ playerId: 'c1', position: 'MID', overall: 85, role: 'BOX_TO_BOX' }),
      player({ playerId: 'c2', position: 'GK', overall: 70, role: 'GOALKEEPER' }),
      player({ playerId: 'c3', position: 'ATT', overall: 90, role: 'POACHER' }),
      player({ playerId: 'c4', position: 'DEF', overall: 80, role: 'DEFENDER' }),
    ]

    const sorted = rankByFit(candidates, null)

    expect(sorted.map(p => p.playerId)).toEqual(['c2', 'c4', 'c1', 'c3'])
  })

  it('an unrecognised position sorts after every known position band, not before GK', () => {
    // position is typed as a plain string (only the domain, not the type, is limited to
    // POSITION_ORDER), so an out-of-catalogue value must fall to the back, matching the
    // byPosition helper this code replaced — not to the front, which indexOf's bare -1 would do.
    const candidates = [
      player({ playerId: 'unknown', position: 'LATERAL_ESQUERDO', overall: 99 }),
      player({ playerId: 'att', position: 'ATT', overall: 50 }),
      player({ playerId: 'gk', position: 'GK', overall: 40 }),
    ]

    const sorted = rankByFit(candidates, null)

    expect(sorted.map(p => p.playerId)).toEqual(['gk', 'att', 'unknown'])
  })

  it('the input array is not mutated', () => {
    const reference = player({ playerId: 'ref', position: 'GK', overall: 75 })
    const candidates = [
      player({ playerId: 'c1', position: 'DEF', overall: 95 }),
      player({ playerId: 'c2', position: 'GK', overall: 60 }),
    ]
    const originalOrder = candidates.map(p => p.playerId)

    rankByFit(candidates, reference)

    expect(candidates.map(p => p.playerId)).toEqual(originalOrder)
  })
})

describe('isSuggestedFor', () => {
  it('is false for every candidate when reference is null', () => {
    const candidates = [
      player({ playerId: 'c1', position: 'GK' }),
      player({ playerId: 'c2', position: 'DEF' }),
    ]

    const results = candidates.map(c => isSuggestedFor(c, null))

    expect(results).toEqual([false, false])
  })

  it('is true only when candidate.position === reference.position', () => {
    const reference = player({ playerId: 'ref', position: 'DEF' })
    const samePosition = player({ playerId: 'c1', position: 'DEF' })
    const differentPosition = player({ playerId: 'c2', position: 'MID' })

    expect(isSuggestedFor(samePosition, reference)).toBe(true)
    expect(isSuggestedFor(differentPosition, reference)).toBe(false)
  })
})

describe('POSITION_ORDER', () => {
  it('exports the position band order as GK < DEF < MID < ATT', () => {
    expect(POSITION_ORDER).toEqual(['GK', 'DEF', 'MID', 'ATT'])
  })
})
