import { useState } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Typography, Box, Avatar, Stack, IconButton, Divider, Paper,
  Tabs, Tab, FormControl, NativeSelect,
} from '@mui/material'
import PersonIcon from '@mui/icons-material/Person'
import CloseIcon from '@mui/icons-material/Close'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import AddIcon from '@mui/icons-material/Add'
import type { LivePlayer, LiveTactics, LiveTacticsRequest, Mentality, GameStyle, Pressing, Marking } from '../../api/types'
import { FitnessBar } from '../squad/FitnessBar'
import { FORMATION_CODES } from '../pitch/pitchSlots'
import { MENTALITY_LABEL, GAME_STYLE_LABEL, PRESSING_LABEL, MARKING_LABEL } from '../squad/tacticsLabels'

// ---------------------------------------------------------------------------
// Shared constants
// ---------------------------------------------------------------------------

const POSITION_ORDER: Record<string, number> = { GK: 0, DEF: 1, MID: 2, ATT: 3 }
function byPosition(a: LivePlayer, b: LivePlayer): number {
  return (POSITION_ORDER[a.position] ?? 9) - (POSITION_ORDER[b.position] ?? 9) || b.overall - a.overall
}

const MENTALITIES = (Object.keys(MENTALITY_LABEL) as Mentality[])
  .map(v => ({ value: v, label: MENTALITY_LABEL[v] }))
const GAME_STYLES = (Object.keys(GAME_STYLE_LABEL) as GameStyle[])
  .map(v => ({ value: v, label: GAME_STYLE_LABEL[v] }))
const PRESSINGS = (Object.keys(PRESSING_LABEL) as Pressing[])
  .map(v => ({ value: v, label: PRESSING_LABEL[v] }))
const MARKINGS = (Object.keys(MARKING_LABEL) as Marking[])
  .map(v => ({ value: v, label: MARKING_LABEL[v] }))
const FORMATIONS = FORMATION_CODES

// ---------------------------------------------------------------------------
// Sub-types
// ---------------------------------------------------------------------------

export interface SubPair {
  out: LivePlayer
  in: LivePlayer
}

// ---------------------------------------------------------------------------
// Shared helper components
// ---------------------------------------------------------------------------

function PlayerCard({
  player,
  selected,
  onClick,
  disabled,
  compact,
}: {
  player: LivePlayer
  selected?: boolean
  onClick?: () => void
  disabled?: boolean
  compact?: boolean
}) {
  return (
    <Paper
      variant="outlined"
      onClick={!disabled ? onClick : undefined}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        p: compact ? 0.5 : 1,
        cursor: disabled ? 'default' : onClick ? 'pointer' : 'default',
        opacity: disabled ? 0.45 : 1,
        border: selected ? '2px solid' : '1px solid',
        borderColor: selected ? 'primary.main' : 'divider',
        bgcolor: selected ? 'action.selected' : 'background.paper',
        borderRadius: 1,
        transition: 'border-color 0.15s, background-color 0.15s',
        '&:hover': !disabled && onClick ? { bgcolor: 'action.hover' } : {},
        minWidth: 0,
      }}
    >
      <Avatar sx={{ width: compact ? 28 : 36, height: compact ? 28 : 36, flexShrink: 0 }}>
        <PersonIcon fontSize={compact ? 'small' : 'medium'} />
      </Avatar>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant={compact ? 'caption' : 'body2'} sx={{ fontWeight: 600, display: 'block', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {player.name}{player.hobbled ? ' 🚑' : ''}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>{player.position}</Typography>
        <FitnessBar fitness={player.fitness} />
      </Box>
    </Paper>
  )
}

function PlaceholderCard({ label }: { label: string }) {
  return (
    <Paper
      variant="outlined"
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 0.5,
        p: 1,
        border: '1px dashed',
        borderColor: 'divider',
        borderRadius: 1,
        minWidth: 100,
        color: 'text.disabled',
      }}
    >
      <AddIcon fontSize="small" />
      <Typography variant="caption">{label}</Typography>
    </Paper>
  )
}

function SelectField({ label, value, onChange, options }: {
  label: string
  value: string
  onChange: (v: string) => void
  options: { value: string; label: string }[]
}) {
  return (
    <FormControl size="small" fullWidth>
      <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.25 }}>{label}</Typography>
      <NativeSelect
        value={value}
        onChange={e => onChange(e.target.value)}
        inputProps={{ 'aria-label': label }}
      >
        {options.map(o => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </NativeSelect>
    </FormControl>
  )
}

// ---------------------------------------------------------------------------
// Tab panel content components
// ---------------------------------------------------------------------------

interface SubTabProps {
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  queue: SubPair[]
  pendingOut: LivePlayer | null
  onSelectOut: (player: LivePlayer) => void
  onSelectIn: (player: LivePlayer) => void
  onRemoveQueued: (index: number) => void
  onCancelPendingOut: () => void
}

function SubTabContent({
  onPitch,
  bench,
  subsRemaining,
  queue,
  pendingOut,
  onSelectOut,
  onSelectIn,
  onRemoveQueued,
  onCancelPendingOut,
}: SubTabProps) {
  const remainingSlots = subsRemaining - queue.length

  const usedOutIds = new Set(queue.map(p => p.out.playerId))
  const usedInIds = new Set(queue.map(p => p.in.playerId))

  const outgoingOptions = onPitch
    .filter(p => !usedOutIds.has(p.playerId))
    .sort(byPosition)

  const incomingOptions = bench
    .filter(b => !b.used && b.eligible && !usedInIds.has(b.playerId))
    .sort(byPosition)

  const emptySlotCount = Math.min(
    Math.max(0, remainingSlots - (pendingOut ? 1 : 0)),
    2,
  )

  return (
    <>
      <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5 }}>
        Substituições Pendentes
      </Typography>

      <Stack spacing={1} sx={{ mb: 2 }}>
        {queue.map((pair, i) => (
          <Box
            key={`${pair.out.playerId}-${pair.in.playerId}`}
            sx={{ display: 'flex', alignItems: 'center', gap: 1 }}
          >
            <Typography variant="caption" sx={{ color: 'text.secondary', minWidth: 16, textAlign: 'right' }}>
              {i + 1}.
            </Typography>
            <Box sx={{ flex: 1, display: 'grid', gridTemplateColumns: '1fr auto 1fr', gap: 0.5, alignItems: 'center' }}>
              <PlayerCard player={pair.out} compact />
              <ArrowForwardIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              <PlayerCard player={pair.in} compact />
            </Box>
            <IconButton size="small" onClick={() => onRemoveQueued(i)} aria-label={`Remover substituição ${i + 1}`}>
              <CloseIcon fontSize="small" />
            </IconButton>
          </Box>
        ))}

        {pendingOut && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="caption" sx={{ color: 'text.secondary', minWidth: 16, textAlign: 'right' }}>
              {queue.length + 1}.
            </Typography>
            <Box sx={{ flex: 1, display: 'grid', gridTemplateColumns: '1fr auto 1fr', gap: 0.5, alignItems: 'center' }}>
              <PlayerCard player={pendingOut} compact selected />
              <ArrowForwardIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              <PlaceholderCard label="Entra" />
            </Box>
            <IconButton size="small" onClick={onCancelPendingOut} aria-label="Cancelar seleção">
              <CloseIcon fontSize="small" />
            </IconButton>
          </Box>
        )}

        {!pendingOut && Array.from({ length: emptySlotCount }).map((_, i) => (
          <Box
            // eslint-disable-next-line react/no-array-index-key
            key={`empty-${i}`}
            sx={{ display: 'flex', alignItems: 'center', gap: 1 }}
          >
            <Typography variant="caption" sx={{ color: 'text.secondary', minWidth: 16, textAlign: 'right' }}>
              {queue.length + i + 1}.
            </Typography>
            <Box sx={{ flex: 1, display: 'grid', gridTemplateColumns: '1fr auto 1fr', gap: 0.5, alignItems: 'center' }}>
              <PlaceholderCard label="Sai" />
              <ArrowForwardIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              <PlaceholderCard label="Entra" />
            </Box>
            <Box sx={{ width: 28 }} />
          </Box>
        ))}

        {remainingSlots === 0 && queue.length === 0 && (
          <Typography variant="body2" sx={{ color: 'text.secondary', px: 1 }}>
            Sem substituições restantes.
          </Typography>
        )}
      </Stack>

      <Divider sx={{ mb: 2 }} />

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
          gap: 2,
        }}
      >
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.7rem', color: 'text.secondary' }}>
            Selecionar quem sai do campo
          </Typography>
          <Stack spacing={0.75}>
            {outgoingOptions.map(p => (
              <PlayerCard
                key={p.playerId}
                player={p}
                selected={pendingOut?.playerId === p.playerId}
                onClick={() => onSelectOut(p)}
                disabled={remainingSlots <= 0}
              />
            ))}
            {outgoingOptions.length === 0 && (
              <Typography variant="body2" sx={{ color: 'text.secondary', p: 1 }}>
                Sem jogadores disponíveis.
              </Typography>
            )}
          </Stack>
        </Box>

        <Box>
          <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.7rem', color: 'text.secondary' }}>
            Selecionar quem entra no jogo
          </Typography>
          <Stack spacing={0.75}>
            {incomingOptions.map(p => (
              <PlayerCard
                key={p.playerId}
                player={p}
                selected={false}
                onClick={() => onSelectIn(p)}
                disabled={!pendingOut}
              />
            ))}
            {incomingOptions.length === 0 && (
              <Typography variant="body2" sx={{ color: 'text.secondary', p: 1 }}>
                Sem reservas disponíveis.
              </Typography>
            )}
          </Stack>
        </Box>
      </Box>
    </>
  )
}

interface TacticsTabProps {
  currentTactics: LiveTactics
  mentality: string
  gameStyle: string
  pressing: string
  marking: string
  formation: string
  onMentalityChange: (v: string) => void
  onGameStyleChange: (v: string) => void
  onPressingChange: (v: string) => void
  onMarkingChange: (v: string) => void
  onFormationChange: (v: string) => void
}

function TacticsTabContent({
  mentality,
  gameStyle,
  pressing,
  marking,
  formation,
  onMentalityChange,
  onGameStyleChange,
  onPressingChange,
  onMarkingChange,
  onFormationChange,
}: TacticsTabProps) {
  return (
    <Stack spacing={2} sx={{ pt: 1 }}>
      <SelectField
        label="Mentalidade"
        value={mentality}
        onChange={onMentalityChange}
        options={MENTALITIES}
      />
      <SelectField
        label="Estilo de jogo"
        value={gameStyle}
        onChange={onGameStyleChange}
        options={GAME_STYLES}
      />
      <SelectField
        label="Pressão"
        value={pressing}
        onChange={onPressingChange}
        options={PRESSINGS}
      />
      <SelectField
        label="Marcação"
        value={marking}
        onChange={onMarkingChange}
        options={MARKINGS}
      />
      <SelectField
        label="Formação"
        value={formation}
        onChange={onFormationChange}
        options={FORMATIONS.map(f => ({ value: f, label: f }))}
      />
    </Stack>
  )
}

// ---------------------------------------------------------------------------
// MatchActionsDialog — single tabbed dialog
// ---------------------------------------------------------------------------

export interface MatchActionsDialogProps {
  open: boolean
  onClose: () => void
  initialTab: 0 | 1
  // Substitution tab props
  onPitch: LivePlayer[]
  bench: LivePlayer[]
  subsRemaining: number
  // Unified apply callback and pending flag
  pending: boolean
  onApply: (payload: { subs: SubPair[]; tactics: LiveTacticsRequest | null }) => void
  // Tactics tab props
  currentTactics: LiveTactics
}

export function MatchActionsDialog({
  open,
  onClose,
  initialTab,
  onPitch,
  bench,
  subsRemaining,
  pending,
  onApply,
  currentTactics,
}: MatchActionsDialogProps) {
  const [activeTab, setActiveTab] = useState<0 | 1>(initialTab)

  // Sub tab state
  const [queue, setQueue] = useState<SubPair[]>([])
  const [pendingOut, setPendingOut] = useState<LivePlayer | null>(null)

  // Tactics tab state — initialised from the current tactics
  const [mentality, setMentality] = useState(currentTactics.mentality)
  const [gameStyle, setGameStyle] = useState(currentTactics.gameStyle)
  const [pressing, setPressing] = useState(currentTactics.pressing)
  const [marking, setMarking] = useState(currentTactics.marking)
  const [formation, setFormation] = useState(currentTactics.formation)

  function handleSelectOut(player: LivePlayer) {
    const remainingSlots = subsRemaining - queue.length
    if (remainingSlots <= 0) return
    setPendingOut(prev => prev?.playerId === player.playerId ? null : player)
  }

  function handleSelectIn(player: LivePlayer) {
    if (!pendingOut) return
    setQueue(prev => [...prev, { out: pendingOut, in: player }])
    setPendingOut(null)
  }

  function handleRemoveQueued(index: number) {
    setQueue(prev => prev.filter((_, i) => i !== index))
  }

  function handleContinue() {
    const tacticsChanged =
      mentality !== currentTactics.mentality ||
      gameStyle !== currentTactics.gameStyle ||
      pressing !== currentTactics.pressing ||
      marking !== currentTactics.marking ||
      formation !== currentTactics.formation

    onApply({
      subs: queue,
      tactics: tacticsChanged ? { mentality, gameStyle, pressing, marking, formation } : null,
    })
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle sx={{ pb: 0 }}>
        Ações da Partida
      </DialogTitle>

      <Box sx={{ borderBottom: 1, borderColor: 'divider', px: 3 }}>
        <Tabs
          value={activeTab}
          onChange={(_, v: 0 | 1) => setActiveTab(v)}
          aria-label="Ações da partida"
        >
          <Tab label="Substituição" id="tab-sub" aria-controls="tabpanel-sub" />
          <Tab label="Instruções táticas" id="tab-tactics" aria-controls="tabpanel-tactics" />
        </Tabs>
      </Box>

      {/* Fixed height so switching tabs never resizes the dialog; the taller
          Substituição tab scrolls internally rather than growing the modal. */}
      <DialogContent sx={{ pb: 0, height: '60vh', overflowY: 'auto' }}>
        <Box
          role="tabpanel"
          id="tabpanel-sub"
          aria-labelledby="tab-sub"
          hidden={activeTab !== 0}
        >
          {activeTab === 0 && (
            <SubTabContent
              onPitch={onPitch}
              bench={bench}
              subsRemaining={subsRemaining}
              queue={queue}
              pendingOut={pendingOut}
              onSelectOut={handleSelectOut}
              onSelectIn={handleSelectIn}
              onRemoveQueued={handleRemoveQueued}
              onCancelPendingOut={() => setPendingOut(null)}
            />
          )}
        </Box>

        <Box
          role="tabpanel"
          id="tabpanel-tactics"
          aria-labelledby="tab-tactics"
          hidden={activeTab !== 1}
        >
          {activeTab === 1 && (
            <TacticsTabContent
              currentTactics={currentTactics}
              mentality={mentality}
              gameStyle={gameStyle}
              pressing={pressing}
              marking={marking}
              formation={formation}
              onMentalityChange={setMentality}
              onGameStyleChange={setGameStyle}
              onPressingChange={setPressing}
              onMarkingChange={setMarking}
              onFormationChange={setFormation}
            />
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ justifyContent: 'space-between', px: 3, py: 2 }}>
        {activeTab === 0 ? (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Limite total de 5 substituições por partida.
          </Typography>
        ) : (
          <Box />
        )}
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button onClick={onClose}>Cancelar</Button>
          <Button
            variant="contained"
            disabled={pending}
            onClick={handleContinue}
          >
            {pending ? 'Aplicando…' : 'Continuar'}
          </Button>
        </Box>
      </DialogActions>
    </Dialog>
  )
}
