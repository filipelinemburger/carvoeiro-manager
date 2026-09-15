import { useState } from 'react'
import { Box, NativeSelect, Typography } from '@mui/material'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { pitchSlots } from '../pitch/pitchSlots'
import { RoleContextMenu, type RoleMenuAnchor } from '../squad/RoleContextMenu'
import { ARCHETYPE_LABEL, ARCHETYPES_FOR_POSITION } from '../players/roleLabels'
import type { LiveKitColors, LivePlayer, RoleArchetype } from '../../api/types'
import type { RoleOverrides, SlotMap } from './liveArrangement'

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
            value={roleOverrides[menuPlayer.playerId] ?? ''}
            onChange={e => onRoleChange(
              menuPlayer.playerId,
              e.target.value === '' ? null : e.target.value as RoleArchetype,
            )}
            inputProps={{ 'aria-label': 'Função' }}
          >
            <option value="">Automática (melhor para a vaga)</option>
            {archetypes.map(a => <option key={a} value={a}>{ARCHETYPE_LABEL[a]}</option>)}
          </NativeSelect>
        ) : null}
        focoControl={<Typography variant="body2" sx={{ color: 'text.secondary' }}>Padrão da função</Typography>}
        onClose={() => setRoleMenu(null)}
      />
    </Box>
  )
}
