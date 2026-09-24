import { useState } from 'react'
import { Box, NativeSelect, Typography } from '@mui/material'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { pitchSlots } from '../pitch/pitchSlots'
import { RoleContextMenu, type RoleMenuAnchor } from '../squad/RoleContextMenu'
import { ARCHETYPE_LABEL, ARCHETYPES_FOR_POSITION } from '../players/roleLabels'
import type { LiveKitColors, LivePlayer, RoleArchetype } from '../../api/types'
import { appliedRole, type RoleOverrides, type SlotMap } from './liveArrangement'

/**
 * Posições: the managed side on the squad screen's own pitch editor. Drag swaps two players' slots; a
 * right-click or long-press opens the role menu. Everything is controlled — the dialog owns the
 * arrangement, so the footer can count it and the apply can send it.
 */
export function PositionsTabContent({ roster, formation, slots, roleOverrides, kit, onSwap, onRoleChange }: {
  roster: LivePlayer[]
  formation: string
  slots: SlotMap
  roleOverrides: RoleOverrides
  kit: LiveKitColors
  onSwap: (fromSlot: number, toSlot: number) => void
  onRoleChange: (playerId: string, archetype: RoleArchetype | null) => void
}) {
  const [roleMenu, setRoleMenu] = useState<RoleMenuAnchor | null>(null)

  const team: TeamOnPitch = {
    formation,
    kit,
    showNames: true,
    players: roster.map((p): PlacedPlayer => ({
      id: p.playerId,
      surname: p.name,
      position: p.position as PlacedPlayer['position'],
      detailedPosition: p.detailedPosition,
      altPositions: p.altPositions,
      fitness: p.fitness,
      slotIndex: slots[p.playerId],
      badges: p.hobbled ? { injured: true } : undefined,
    })),
  }

  const menuPlayer = roster.find(p => p.playerId === roleMenu?.playerId) ?? null
  const menuSlot = menuPlayer ? pitchSlots(formation)[slots[menuPlayer.playerId]] : undefined
  const archetypes = menuSlot ? ARCHETYPES_FOR_POSITION[menuSlot.accepted[0]] : []
  // What the menu opens on: a role the manager has just picked, else the one the player is actually
  // playing. "Automática" is offered only when it is the truth — once the engine is applying a named
  // role, picking "automática" would be a no-op (the engine keeps an unmoved player's role), so the
  // option that would lie about doing something is not there to pick.
  const menuRole = menuPlayer ? appliedRole(menuPlayer, slots, archetypes) : ''
  const selectedRole = menuPlayer ? roleOverrides[menuPlayer.playerId] ?? menuRole : ''

  return (
    <Box sx={{ pt: 1 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 1 }}>
        Arraste um jogador para trocar de posição. Clique com o botão direito (ou segure) para escolher a função.
      </Typography>
      <Pitch
        home={team}
        onSwap={onSwap}
        onSlotContextMenu={(slotIndex, x, y) => {
          const playerId = Object.keys(slots).find(id => slots[id] === slotIndex)
          if (playerId) setRoleMenu({ playerId, x, y })
        }}
      />
      <RoleContextMenu
        anchor={menuPlayer ? roleMenu : null}
        playerName={menuPlayer?.name ?? ''}
        funcaoControl={menuPlayer ? (
          <NativeSelect
            fullWidth
            value={selectedRole}
            onChange={e => onRoleChange(
              menuPlayer.playerId,
              e.target.value === '' ? null : e.target.value as RoleArchetype,
            )}
            inputProps={{ 'aria-label': 'Função' }}
          >
            {menuRole === '' && <option value="">Automática (melhor para a vaga)</option>}
            {archetypes.map(a => <option key={a} value={a}>{ARCHETYPE_LABEL[a]}</option>)}
          </NativeSelect>
        ) : null}
        focoControl={<Typography variant="body2" sx={{ color: 'text.secondary' }}>Padrão da função</Typography>}
        onClose={() => setRoleMenu(null)}
      />
    </Box>
  )
}
