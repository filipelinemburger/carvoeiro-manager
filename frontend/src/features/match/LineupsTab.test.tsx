import { describe, it, expect } from 'vitest'
import { buildTeam } from './LineupsTab'
import type { LivePlayer } from '../../api/types'

const KIT = { primary: '#ffcc00', secondary: '#000000' }

function livePlayer(overrides: Partial<LivePlayer> & Pick<LivePlayer, 'playerId'>): LivePlayer {
  return {
    name: 'Jogador', position: 'DEF', overall: 70, fitness: 100,
    hobbled: false, onPitch: true, used: false, eligible: true, role: 'STOPPER',
    slotIndex: null, detailedPosition: null, altPositions: [],
    ...overrides,
  }
}

describe('buildTeam', () => {
  it('carries the server slot index and positions into the pitch view-model', () => {
    const team = buildTeam(
      [livePlayer({ playerId: 'z', slotIndex: 2, detailedPosition: 'ZAGUEIRO', altPositions: ['VOLANTE'] })],
      '4-4-2', KIT,
    )

    expect(team.players[0]).toMatchObject({
      id: 'z', slotIndex: 2, detailedPosition: 'ZAGUEIRO', altPositions: ['VOLANTE'],
    })
  })

  it('leaves slotIndex undefined when the server sent none, so the pitch falls back to its line-zip', () => {
    const team = buildTeam([livePlayer({ playerId: 'z', slotIndex: null })], '4-4-2', KIT)

    expect(team.players[0].slotIndex).toBeUndefined()
  })
})
