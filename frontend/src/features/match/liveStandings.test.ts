import { describe, it, expect } from 'vitest'
import { liveStandings } from './liveStandings'
import type { StandingRow } from '../../api/types'

function row(overrides: Partial<StandingRow> & { clubId: string; clubName: string }): StandingRow {
  const played = overrides.played ?? 0
  const goalsFor = overrides.goalsFor ?? 0
  const goalsAgainst = overrides.goalsAgainst ?? 0
  return {
    played,
    won: 0,
    drawn: 0,
    lost: 0,
    goalsFor,
    goalsAgainst,
    goalDifference: goalsFor - goalsAgainst,
    points: 0,
    ...overrides,
  }
}

const ids = (rows: StandingRow[]) => rows.map(r => r.clubId)
const find = (rows: StandingRow[], clubId: string) => {
  const found = rows.find(r => r.clubId === clubId)
  if (!found) throw new Error(`No row for ${clubId}`)
  return found
}

describe('liveStandings', () => {
  it('gives the winner 3 points and the loser none, with the goals on the right sides', () => {
    const base = [
      row({ clubId: 'a', clubName: 'Alfa' }),
      row({ clubId: 'b', clubName: 'Beta' }),
    ]

    const live = liveStandings(base, [{ homeClubId: 'a', awayClubId: 'b', homeGoals: 3, awayGoals: 1 }])

    expect(find(live, 'a')).toMatchObject({
      played: 1, won: 1, drawn: 0, lost: 0, goalsFor: 3, goalsAgainst: 1, goalDifference: 2, points: 3,
    })
    expect(find(live, 'b')).toMatchObject({
      played: 1, won: 0, drawn: 0, lost: 1, goalsFor: 1, goalsAgainst: 3, goalDifference: -2, points: 0,
    })
  })

  it('gives both clubs a point on a draw', () => {
    const base = [
      row({ clubId: 'a', clubName: 'Alfa' }),
      row({ clubId: 'b', clubName: 'Beta' }),
    ]

    const live = liveStandings(base, [{ homeClubId: 'a', awayClubId: 'b', homeGoals: 2, awayGoals: 2 }])

    expect(find(live, 'a')).toMatchObject({
      played: 1, won: 0, drawn: 1, lost: 0, goalsFor: 2, goalsAgainst: 2, goalDifference: 0, points: 1,
    })
    expect(find(live, 'b')).toMatchObject({
      played: 1, won: 0, drawn: 1, lost: 0, goalsFor: 2, goalsAgainst: 2, goalDifference: 0, points: 1,
    })
  })

  it('counts a goalless draw as a played match for both clubs', () => {
    const base = [
      row({ clubId: 'a', clubName: 'Alfa' }),
      row({ clubId: 'b', clubName: 'Beta' }),
    ]

    const live = liveStandings(base, [{ homeClubId: 'a', awayClubId: 'b', homeGoals: 0, awayGoals: 0 }])

    expect(find(live, 'a')).toMatchObject({ played: 1, drawn: 1, points: 1, goalsFor: 0, goalsAgainst: 0 })
    expect(find(live, 'b')).toMatchObject({ played: 1, drawn: 1, points: 1, goalsFor: 0, goalsAgainst: 0 })
  })

  it('moves a club above one it overtakes on points', () => {
    const base = [
      row({ clubId: 'leader', clubName: 'Leader', played: 3, won: 2, drawn: 0, lost: 1, goalsFor: 6, goalsAgainst: 3, points: 6 }),
      row({ clubId: 'chaser', clubName: 'Chaser', played: 3, won: 1, drawn: 1, lost: 1, goalsFor: 4, goalsAgainst: 3, points: 4 }),
      row({ clubId: 'punchbag', clubName: 'Punchbag', played: 3, won: 0, drawn: 0, lost: 3, goalsFor: 0, goalsAgainst: 5, points: 0 }),
    ]
    expect(ids(base)).toEqual(['leader', 'chaser', 'punchbag'])

    const live = liveStandings(base, [{ homeClubId: 'chaser', awayClubId: 'punchbag', homeGoals: 1, awayGoals: 0 }])

    // chaser 4 + 3 = 7 > leader 6
    expect(find(live, 'chaser')).toMatchObject({ points: 7 })
    expect(find(live, 'leader')).toMatchObject({ points: 6 })
    expect(ids(live)).toEqual(['chaser', 'leader', 'punchbag'])
  })

  it('breaks a points tie on goal difference, so a bigger win jumps the club', () => {
    const base = [
      row({ clubId: 'ahead', clubName: 'Ahead', played: 3, won: 2, drawn: 0, lost: 1, goalsFor: 6, goalsAgainst: 1, points: 6 }),
      row({ clubId: 'behind', clubName: 'Behind', played: 3, won: 1, drawn: 0, lost: 2, goalsFor: 4, goalsAgainst: 3, points: 3 }),
    ]

    // behind wins 5-0: 3 + 3 = 6 points (level with ahead), GD +6 vs +5.
    const live = liveStandings(base, [{ homeClubId: 'behind', awayClubId: 'ahead', homeGoals: 5, awayGoals: 0 }])

    expect(find(live, 'behind')).toMatchObject({ points: 6, goalDifference: 6 })
    expect(find(live, 'ahead')).toMatchObject({ points: 6, goalDifference: 0 })
    expect(ids(live)).toEqual(['behind', 'ahead'])
  })

  it('falls back to clubName ascending when points, goal difference and goals for are all level', () => {
    // Zed is listed first in `base`; after the round both clubs are identical on every
    // numeric column, so only the clubName tie-break can decide the order.
    const base = [
      row({ clubId: 'z', clubName: 'Zed', commonName: 'Zeta' }),
      row({ clubId: 'a', clubName: 'Ajax', commonName: 'Zzz' }),
      row({ clubId: 'x', clubName: 'Xis' }),
      row({ clubId: 'y', clubName: 'Ypsilon' }),
    ]

    const live = liveStandings(base, [
      { homeClubId: 'z', awayClubId: 'x', homeGoals: 2, awayGoals: 0 },
      { homeClubId: 'a', awayClubId: 'y', homeGoals: 2, awayGoals: 0 },
    ])

    expect(find(live, 'z')).toMatchObject({ points: 3, goalDifference: 2, goalsFor: 2 })
    expect(find(live, 'a')).toMatchObject({ points: 3, goalDifference: 2, goalsFor: 2 })
    // clubName asc: Ajax < Zed. Sorting on commonName ("Zeta" < "Zzz") would give the reverse.
    expect(ids(live).slice(0, 2)).toEqual(['a', 'z'])
    // The two winless clubs are also ordered by clubName: Xis < Ypsilon.
    expect(ids(live).slice(2)).toEqual(['x', 'y'])
  })

  it('skips a result naming a club that is not in the base table, without throwing', () => {
    const base = [
      row({ clubId: 'a', clubName: 'Alfa' }),
      row({ clubId: 'b', clubName: 'Beta' }),
    ]

    const live = liveStandings(base, [
      { homeClubId: 'a', awayClubId: 'ghost', homeGoals: 4, awayGoals: 0 },
      { homeClubId: 'ghost', awayClubId: 'phantom', homeGoals: 1, awayGoals: 1 },
      { homeClubId: 'a', awayClubId: 'b', homeGoals: 1, awayGoals: 0 },
    ])

    // No invented rows: only the two clubs the base knows about.
    expect(ids(live).sort()).toEqual(['a', 'b'])
    // Only the a-vs-b result counted; the two ghost fixtures left no trace on `a`.
    expect(find(live, 'a')).toMatchObject({ played: 1, won: 1, goalsFor: 1, goalsAgainst: 0, points: 3 })
    expect(find(live, 'b')).toMatchObject({ played: 1, lost: 1, goalsFor: 0, goalsAgainst: 1, points: 0 })
  })

  it('does not mutate the base rows it was given', () => {
    const base = [
      row({ clubId: 'a', clubName: 'Alfa', played: 3, won: 2, drawn: 0, lost: 1, goalsFor: 6, goalsAgainst: 3, points: 6 }),
      row({ clubId: 'b', clubName: 'Beta', played: 3, won: 1, drawn: 1, lost: 1, goalsFor: 4, goalsAgainst: 4, points: 4 }),
    ]
    const snapshot = structuredClone(base)

    const live = liveStandings(base, [{ homeClubId: 'b', awayClubId: 'a', homeGoals: 3, awayGoals: 0 }])

    // The returned table really did change...
    expect(find(live, 'b')).toMatchObject({ played: 4, points: 7 })
    // ...and the caller's array and rows are untouched, contents and order alike.
    expect(base).toEqual(snapshot)
    expect(base[0]).not.toBe(live.find(r => r.clubId === 'a'))
  })
})
