import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, Stack, Typography, Button, Chip, Alert, Snackbar, FormControl, NativeSelect, Link,
  Table, TableHead, TableBody, TableRow, TableCell, TableContainer, Paper, Card, CardContent,
  Tabs, Tab, Collapse, IconButton,
} from '@mui/material'
import HealingIcon from '@mui/icons-material/Healing'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import { getSquad, getLineup, setLineup, setTraining, renewContract, listPlayer, unlistPlayer } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { PlayerResponse, PlayerRole, PlayerInstruction, LineupStarter, TrainableAttribute, TrainingIntensity, TrainingAssignment, SquadResponse } from '../../api/types'
import { TacticsPanel } from './TacticsPanel'
import { PlayerDetails } from './PlayerDetails'
import { FitnessBar } from './FitnessBar'
import { MoodChip } from './MoodChip'
import { teamMorale } from './morale'
import { SEVERITY_LABEL } from './injurySeverity'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { FORMATION_CODES, pitchSlots } from '../pitch/pitchSlots'
import { assignSlots, type AssignablePlayer } from '../pitch/slotAssign'
import { positionShort } from '../players/positionLabels'
import { CARBON, ovrBadgeSx } from '../../muiTheme'

const FORMATIONS = FORMATION_CODES

type OutfieldPosition = 'DEF' | 'MID' | 'ATT'

// Outfield players required per line for each formation (goalkeeper is always 1).
// Numbers collapse the deeper shapes (e.g. 4-2-3-1 → 4 DEF, 5 MID, 1 ATT) and each
// row sums to 10, so a full XI is exactly 1 GK + these.
export const FORMATION_SHAPE: Record<string, Record<OutfieldPosition, number>> = {
  '4-4-2': { DEF: 4, MID: 4, ATT: 2 },
  '4-3-3': { DEF: 4, MID: 3, ATT: 3 },
  '3-5-2': { DEF: 3, MID: 5, ATT: 2 },
  '4-2-3-1': { DEF: 4, MID: 5, ATT: 1 },
  '5-3-2': { DEF: 5, MID: 3, ATT: 2 },
  '4-5-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-1-4-1': { DEF: 4, MID: 5, ATT: 1 },
  '4-3-2-1': { DEF: 4, MID: 5, ATT: 1 },
  '3-4-3': { DEF: 3, MID: 4, ATT: 3 },
  // Mirrors the backend FormationSlots: the withdrawn forward is an advanced playmaker (MID), not an ATT.
  '4-4-1-1': { DEF: 4, MID: 5, ATT: 1 },
  '5-4-1': { DEF: 5, MID: 4, ATT: 1 },
  '4-2-4': { DEF: 4, MID: 2, ATT: 4 },
  '3-4-2-1': { DEF: 3, MID: 6, ATT: 1 },
  '4-1-2-1-2': { DEF: 4, MID: 4, ATT: 2 },
  '5-2-3': { DEF: 5, MID: 2, ATT: 3 },
  '4-3-1-2': { DEF: 4, MID: 4, ATT: 2 },
}

// Display order for the squad list: goalkeepers, then defenders, midfielders, attackers.
const POSITION_ORDER: Record<PlayerResponse['position'], number> = { GK: 0, DEF: 1, MID: 2, ATT: 3 }

const ROLES_BY_POSITION: Record<PlayerResponse['position'], PlayerRole[]> = {
  GK: ['GOALKEEPER'],
  DEF: ['STOPPER', 'FULL_BACK', 'BALL_PLAYING_DEF'],
  MID: ['HOLDING_MID', 'BOX_TO_BOX', 'PLAYMAKER'],
  ATT: ['POACHER', 'TARGET_MAN', 'WINGER'],
}

const DEFAULT_ROLE: Record<PlayerResponse['position'], PlayerRole> = {
  GK: 'GOALKEEPER',
  DEF: 'FULL_BACK',
  MID: 'BOX_TO_BOX',
  ATT: 'POACHER',
}

// Human-readable Portuguese labels for the tactical roles. The <option> value stays the
// enum (what the API expects); only the displayed text changes.
const ROLE_LABEL: Record<PlayerRole, string> = {
  GOALKEEPER: 'Goleiro',
  STOPPER: 'Zagueiro',
  FULL_BACK: 'Lateral',
  BALL_PLAYING_DEF: 'Zagueiro construtor',
  HOLDING_MID: 'Volante',
  BOX_TO_BOX: 'Meia box a box',
  PLAYMAKER: 'Armador',
  POACHER: 'Finalizador',
  TARGET_MAN: 'Centroavante',
  WINGER: 'Ponta',
}

// Human-readable Portuguese labels for the optional per-player instruction. The <option> value
// stays the enum (what the API expects); only the displayed text changes.
const INSTRUCTION_LABEL: Record<PlayerInstruction, string> = {
  INFILTRAR_NA_AREA: 'Infiltrar na área',
  CHUTAR_DE_LONGE: 'Chutar de longe',
  CRUZAR_MAIS: 'Cruzar mais',
  SEGURAR_POSICAO: 'Segurar posição',
  MARCACAO_DURA: 'Marcação dura',
  DRIBLAR_MAIS: 'Driblar mais',
}

const TRAINABLE_ATTRIBUTE_LABEL: Record<TrainableAttribute, string> = {
  VELOCIDADE: 'Velocidade',
  FINALIZACAO: 'Finalização',
  PASSE: 'Passe',
  DESARME: 'Desarme',
  REFLEXOS: 'Reflexos',
  FOLEGO: 'Fôlego',
  CABECEIO: 'Cabeceio',
  CRUZAMENTO: 'Cruzamento',
  MARCACAO: 'Marcação',
  DRIBLE: 'Drible',
  VISAO: 'Visão de jogo',
  FORCA: 'Força',
}

const INTENSITY_LABEL: Record<TrainingIntensity, string> = {
  LEVE: 'Leve',
  NORMAL: 'Normal',
  INTENSA: 'Intensa',
}

// Championship-Manager-style squad-slot abbreviation shown on the selection button.
const POS_ABBR: Record<PlayerResponse['position'], string> = { GK: 'GK', DEF: 'DC', MID: 'MC', ATT: 'AT' }

// Criciúma yellow — every starter's selection button.
const CRICIUMA_YELLOW = '#f7d417'

// Coarse fallbacks when a player has no detailed position (legacy payloads).
const COARSE_POS_SHORT: Record<PlayerResponse['position'], string> = { GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }

/** Short position abbr for a squad-table cell: detailed when present, else coarse. */
function positionLabel(player: PlayerResponse): string {
  return positionShort(player, COARSE_POS_SHORT)
}

function formLabel(form: number): string {
  if (form > 0) return `▲ +${form}`
  if (form < 0) return `▼ ${form}`
  return '–'
}

/** Count selected players per position band. */
function countByPosition(selected: string[], players: PlayerResponse[]): Record<PlayerResponse['position'], number> {
  const counts: Record<PlayerResponse['position'], number> = { GK: 0, DEF: 0, MID: 0, ATT: 0 }
  for (const p of players) {
    if (selected.includes(p.id)) counts[p.position]++
  }
  return counts
}

function validate(selected: string[], players: PlayerResponse[],
                  slotByPlayerId: Record<string, number>): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const counts = countByPosition(selected, players)
  if (counts.GK !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
  }
  // Every starter needs a distinct formation slot (drag-and-drop / auto-assign keeps this
  // true; the guard catches degenerate states like a saved starter no longer in the squad).
  const assigned = new Set(selected.map(id => slotByPlayerId[id]).filter(v => v !== undefined))
  if (assigned.size !== 11) {
    return 'Cada titular precisa de uma posição no campo — use o campo na aba Táticas para organizar.'
  }
  // A saved XI can contain a player who has since become unavailable (suspended now;
  // injured once that lands — both flow through `eligible`). Block saving and make the
  // user pick a replacement themselves rather than auto-substituting.
  const unavailable = players.filter(p => selected.includes(p.id) && !p.eligible)
  if (unavailable.length > 0) {
    return `Jogador(es) indisponível(is) na escalação: ${unavailable.map(p => p.name).join(', ')}. Escolha um substituto.`
  }
  return null
}

export function SquadPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const [formation, setFormation] = useState('4-3-3')
  const [selected, setSelected] = useState<string[]>([])
  // Bench is slotted: index i is "Sub i+1"; a null is an empty slot left by a removal, so
  // removing a substitute never renumbers the others — the hole is reused on the next add.
  const [bench, setBench] = useState<(string | null)[]>([])
  const [roles, setRoles] = useState<Record<string, PlayerRole>>({})
  // Per-player instruction keyed by playerId; '' is "Nenhuma" (no instruction). Kept in step with
  // `roles`: every place that seeds or clears a role does the same to the instruction.
  const [instructions, setInstructions] = useState<Record<string, PlayerInstruction | ''>>({})
  const [saveSuccess, setSaveSuccess] = useState(false)
  const [detailsPlayerId, setDetailsPlayerId] = useState<string | null>(null)
  const [tab, setTab] = useState(0)
  const [trainingAssignments, setTrainingAssignments] = useState<
    Record<string, { focus: TrainableAttribute | null; intensity: TrainingIntensity }>
  >({})
  const [trainingSaveSuccess, setTrainingSaveSuccess] = useState(false)
  // playerId -> formation slot index (0 = GK). Keys ⊆ selected; values unique. The pitch
  // renders from this map and drag-and-drop edits it.
  const [slotByPlayerId, setSlotByPlayerId] = useState<Record<string, number>>({})

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  const players = data?.players ?? []
  const slots = pitchSlots(formation)

  const toAssignable = (p: PlayerResponse): AssignablePlayer =>
    ({ id: p.id, position: p.position, detailedPosition: p.detailedPosition, overall: p.overall })

  // Tracks the query cache (rather than a snapshot taken at click time) so the open dialog
  // reflects updates — e.g. an optimistic contract renewal — without the user closing/reopening.
  const detailsPlayer = players.find(p => p.id === detailsPlayerId) ?? null

  // Squad list ordered by position (GK → DEF → MEI → ATA), then overall within each line.
  const displayPlayers = [...players].sort(
    (a, b) => POSITION_ORDER[a.position] - POSITION_ORDER[b.position] || b.overall - a.overall,
  )

  // Load the previously saved lineup so the screen reopens with the same XI/formation
  // instead of resetting to the defaults.
  const savedLineup = useQuery({
    queryKey: ['lineup', saveGameId, clubId],
    queryFn: () => getLineup(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  // Seed the form from the saved lineup exactly once. A guard prevents later refetches
  // (e.g. the save mutation's invalidation) from clobbering the user's in-progress edits.
  // Also waits for the squad (`data`) so the legacy fallback below can read player attributes.
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current || !savedLineup.isSuccess || !data) return
    const lineup = savedLineup.data
    if (lineup) {
      setFormation(lineup.formation)
      setSelected(lineup.starters.map(s => s.playerId))
      setRoles(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.role])))
      setInstructions(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.instruction ?? ''])))
      setBench((lineup.bench ?? []).map(s => s.playerId))
      if (lineup.starters.every(s => s.slotIndex != null)) {
        setSlotByPlayerId(Object.fromEntries(
          lineup.starters.map(s => [s.playerId, s.slotIndex as number])))
      } else {
        // Legacy lineup without persisted slots: derive the same default the engine would.
        const xi = lineup.starters
          .map(s => data.players.find(p => p.id === s.playerId))
          .filter((p): p is PlayerResponse => Boolean(p))
        setSlotByPlayerId(assignSlots(xi.map(toAssignable), lineup.formation))
      }
    }
    hydrated.current = true
  }, [savedLineup.isSuccess, savedLineup.data, data])

  // Seed the training tab from the squad query exactly once, the same way the lineup form is
  // seeded from its own query above — a separate ref so the two hydrations don't interfere.
  const trainingHydrated = useRef(false)
  useEffect(() => {
    if (trainingHydrated.current || !data) return
    setTrainingAssignments(Object.fromEntries(data.players.map(p => [
      p.id,
      { focus: p.trainingFocus ?? null, intensity: p.trainingIntensity ?? 'NORMAL' },
    ])))
    trainingHydrated.current = true
  }, [data])

  const mutation = useMutation({
    mutationFn: () => {
      const toStarter = (id: string, slotIndex: number | null): LineupStarter => {
        const player = players.find(p => p.id === id)!
        return {
          playerId: id,
          role: roles[id] ?? DEFAULT_ROLE[player.position],
          instruction: instructions[id] || null,
          slotIndex,
        }
      }
      const starters: LineupStarter[] = selected.map(id => toStarter(id, slotByPlayerId[id] ?? null))
      const benchStarters: LineupStarter[] = bench
        .filter((id): id is string => id !== null)
        .map(id => toStarter(id, null))
      return setLineup(saveGameId, clubId, { formation, starters, bench: benchStarters }, token ?? '')
    },
    onSuccess: (data) => {
      setSaveSuccess(true)
      // Write-through: the response is already this exact lineup query's fresh data — every
      // observer (this page's own savedLineup query AND Layout's advance-gate query, since both
      // key on the same [saveGameId, clubId]) picks it up immediately, no extra round-trip.
      queryClient.setQueryData(['lineup', saveGameId, clubId], data)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      // Re-check the advance gate: fixing the lineup may clear an ineligible-starter block.
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })

  const trainingMutation = useMutation({
    mutationFn: () => {
      const assignments: TrainingAssignment[] = displayPlayers.map(p => ({
        playerId: p.id,
        focus: trainingAssignments[p.id]?.focus ?? null,
        intensity: trainingAssignments[p.id]?.intensity ?? 'NORMAL',
      }))
      return setTraining(saveGameId, clubId, { assignments }, token ?? '')
    },
    onSuccess: (data) => {
      setTrainingSaveSuccess(true)
      // Write-through: setTraining already returns a SquadResponse — the exact shape of the
      // squad query — so patch the cache directly instead of paying for a refetch.
      queryClient.setQueryData(['squad', saveGameId, clubId], data)
    },
  })

  const renewMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      renewContract(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onMutate: async (v) => {
      await queryClient.cancelQueries({ queryKey: ['squad', saveGameId, clubId] })
      const previous = queryClient.getQueryData<SquadResponse>(['squad', saveGameId, clubId])
      // Patch the contract term immediately so the still-open details dialog (which now derives
      // from this cache) reflects the renewal without waiting on the round-trip.
      queryClient.setQueryData<SquadResponse>(['squad', saveGameId, clubId], (old) =>
        old ? { ...old, players: old.players.map(p => (p.id === v.playerId ? { ...p, contractYears: v.contractYears } : p)) } : old)
      return { previous }
    },
    onError: (_err, _v, context) => {
      if (context?.previous) queryClient.setQueryData(['squad', saveGameId, clubId], context.previous)
    },
    onSettled: () => { void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] }) },
  })

  const listMut = useMutation({
    mutationFn: (playerId: string) => listPlayer(saveGameId, playerId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    },
  })
  const unlistMut = useMutation({
    mutationFn: (playerId: string) => unlistPlayer(saveGameId, playerId, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    },
  })

  const validationError = players.length > 0 ? validate(selected, players, slotByPlayerId) : null
  const canSave = validationError === null

  // Read-only pitch view-model: the current formation is always drawn in full;
  // slots without a selected player stay on the pitch as nameless placeholders.
  const pitchTeam: TeamOnPitch | null = data ? {
    formation,
    kit: { primary: data.primaryColor, secondary: data.secondaryColor },
    showNames: true,
    players: selected
      .map(id => players.find(p => p.id === id))
      .filter((p): p is PlayerResponse => Boolean(p))
      .map((p): PlacedPlayer => ({
        id: p.id, surname: p.name, position: p.position,
        detailedPosition: p.detailedPosition, fitness: p.fitness,
        slotIndex: slotByPlayerId[p.id],
      })),
  } : null

  // First free slot for a newly picked starter: an exact detailed-position slot first, else
  // any free slot of the player's coarse line. Cross-line placement only happens by dragging.
  function firstFreeSlotFor(player: PlayerResponse): number | null {
    const taken = new Set(Object.values(slotByPlayerId))
    const free = slots.map((slot, index) => ({ slot, index })).filter(x => !taken.has(x.index))
    const exact = player.detailedPosition
      ? free.find(x => x.slot.accepted.includes(player.detailedPosition!))
      : undefined
    return (exact ?? free.find(x => x.slot.line === player.position))?.index ?? null
  }

  type Involvement = { kind: 'OUT' } | { kind: 'TITULAR' } | { kind: 'RESERVA'; sub: number }

  // A player is either a starter, a numbered substitute, or unpicked.
  function involvementOf(playerId: string): Involvement {
    if (selected.includes(playerId)) return { kind: 'TITULAR' }
    const benchIndex = bench.indexOf(playerId)
    if (benchIndex >= 0) return { kind: 'RESERVA', sub: benchIndex + 1 }
    return { kind: 'OUT' }
  }

  // One-click squad assignment (Championship-Manager style): clicking an unpicked player
  // sends them to the next available slot — a starting place in their position band when the
  // band has room, otherwise the next numbered bench place. Clicking a picked player removes
  // them. XI and bench are one list; no separate bench control.
  function cyclePlayer(player: PlayerResponse) {
    setSaveSuccess(false)
    if (involvementOf(player.id).kind !== 'OUT') {
      // Removing is always allowed (even for a now-ineligible saved starter). A removed
      // substitute leaves its bench slot empty (null) instead of renumbering the others; the
      // freed "Sub N" is reused by the next player sent to the bench. Drop the role too.
      setSelected(prev => prev.filter(id => id !== player.id))
      setBench(prev => prev.map(id => (id === player.id ? null : id)))
      setRoles(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
      // Drop any personal instruction too, so a later re-add doesn't inherit a stale one.
      setInstructions(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
      setSlotByPlayerId(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
      return
    }
    if (!player.eligible) return
    const slotIndex = firstFreeSlotFor(player)
    if (slotIndex !== null) {
      setSelected(prev => [...prev, player.id])
      setSlotByPlayerId(prev => ({ ...prev, [player.id]: slotIndex }))
      setRoles(prev => ({ ...prev, [player.id]: DEFAULT_ROLE[player.position] }))
      return
    }
    // Starting band full → take the next free bench place: reuse the lowest empty slot (a
    // hole from an earlier removal) before appending, so bench numbers stay stable. Capped at 9.
    const benchHasRoom = bench.some(id => id === null) || bench.length < 9
    if (benchHasRoom) {
      setBench(prev => {
        const hole = prev.findIndex(id => id === null)
        if (hole >= 0) {
          const next = [...prev]
          next[hole] = player.id
          return next
        }
        return [...prev, player.id]
      })
      setRoles(prev => ({ ...prev, [player.id]: DEFAULT_ROLE[player.position] }))
    }
  }

  function changeRole(player: PlayerResponse, role: PlayerRole) {
    setRoles(prev => ({ ...prev, [player.id]: role }))
  }

  function changeInstruction(player: PlayerResponse, value: PlayerInstruction | '') {
    setInstructions(prev => ({ ...prev, [player.id]: value }))
  }

  function changeTrainingFocus(player: PlayerResponse, focus: TrainableAttribute | ''): void {
    setTrainingAssignments(prev => ({
      ...prev,
      [player.id]: { focus: focus === '' ? null : focus, intensity: prev[player.id]?.intensity ?? 'NORMAL' },
    }))
  }

  function changeTrainingIntensity(player: PlayerResponse, intensity: TrainingIntensity): void {
    setTrainingAssignments(prev => ({
      ...prev,
      [player.id]: { focus: prev[player.id]?.focus ?? null, intensity },
    }))
  }

  // Fills the XI with the highest-rated eligible players for each line of the current
  // formation (1 GK + the formation's DEF/MID/ATT counts).
  function autoPickBestXI() {
    setSaveSuccess(false)
    const shape = FORMATION_SHAPE[formation]
    const bestFor = (position: PlayerResponse['position'], count: number) =>
      players
        .filter(p => p.eligible && p.position === position)
        .sort((a, b) => b.overall - a.overall)
        .slice(0, count)
    const picked = [
      ...bestFor('GK', 1),
      ...bestFor('DEF', shape.DEF),
      ...bestFor('MID', shape.MID),
      ...bestFor('ATT', shape.ATT),
    ]
    const pickedIds = picked.map(p => p.id)
    setSelected(pickedIds)
    // Any bench player promoted into the XI vacates their bench slot (keeps the rest stable).
    setBench(prev => prev.map(id => (id !== null && pickedIds.includes(id) ? null : id)))
    setRoles(Object.fromEntries(picked.map(p => [p.id, DEFAULT_ROLE[p.position]])))
    // Fresh auto-pick: every picked player starts with no personal instruction.
    setInstructions(Object.fromEntries(picked.map(p => [p.id, ''])))
    setSlotByPlayerId(assignSlots(picked.map(toAssignable), formation))
  }

  // Empties the whole selection.
  function clearSelection() {
    setSaveSuccess(false)
    setSelected([])
    setRoles({})
    setInstructions({})
    setBench([])
    setSlotByPlayerId({})
  }

  // Formation switch: re-derives slot assignments for the CURRENT selection (rather than
  // resetting it) so an in-progress XI survives the change, out-of-position or not.
  function changeFormation(next: string) {
    setSaveSuccess(false)
    setFormation(next)
    const xi = selected
      .map(id => players.find(p => p.id === id))
      .filter((p): p is PlayerResponse => Boolean(p))
    setSlotByPlayerId(assignSlots(xi.map(toAssignable), next))
  }

  // Drop from the pitch: the dragged player takes the target slot; a previous occupant takes
  // the vacated one (swap). Pitch guarantees fromSlot holds a player and toSlot is not the GK.
  function swapSlots(fromSlot: number, toSlot: number) {
    setSaveSuccess(false)
    setSlotByPlayerId(prev => {
      const fromId = Object.keys(prev).find(id => prev[id] === fromSlot)
      if (!fromId) return prev
      const toId = Object.keys(prev).find(id => prev[id] === toSlot)
      const next = { ...prev, [fromId]: toSlot }
      if (toId) next[toId] = fromSlot
      return next
    })
  }

  // ── Per-player controls, shared by the desktop table and the mobile cards ──

  // The player's name doubles as the trigger for the details dialog.
  function playerNameButton(player: PlayerResponse) {
    return (
      <Link
        component="button"
        type="button"
        underline="hover"
        color="inherit"
        onClick={() => setDetailsPlayerId(player.id)}
        sx={{ font: 'inherit', fontWeight: 600, textAlign: 'left', verticalAlign: 'baseline', cursor: 'pointer' }}
      >
        {player.name}
      </Link>
    )
  }

  // Availability shown as an icon (a red card for a suspension, a bandage for an injury)
  // plus the number of matches remaining. The full wording lives in the hover tooltip.
  function availabilityBadge(player: PlayerResponse) {
    const wrapperSx = { display: 'inline-flex', alignItems: 'center', gap: 0.25, ml: 0.75, verticalAlign: 'middle' } as const
    if (player.suspensionRemaining > 0) {
      const label = `Suspenso — ${player.suspensionRemaining} jogo(s)`
      return (
        <Box component="span" role="img" aria-label={label} title={label} sx={wrapperSx}>
          <Box component="span" sx={{ width: 11, height: 15, borderRadius: '2px', bgcolor: '#d32f2f', border: '1px solid #7f1010' }} />
          <Typography component="span" color="error" sx={{ fontSize: '0.72rem', fontWeight: 700 }}>{player.suspensionRemaining}</Typography>
        </Box>
      )
    }
    if (player.injuryMatches > 0) {
      const tier = SEVERITY_LABEL[player.injurySeverity] ?? ''
      const label = tier ? `Lesionado (${tier}) — ${player.injuryMatches} jogo(s)` : `Lesionado — ${player.injuryMatches} jogo(s)`
      return (
        <Box component="span" role="img" aria-label={label} title={label} sx={wrapperSx}>
          <HealingIcon sx={{ fontSize: 16, color: 'error.main' }} />
          <Typography component="span" color="error" sx={{ fontSize: '0.72rem', fontWeight: 700 }}>{player.injuryMatches}</Typography>
        </Box>
      )
    }
    return null
  }

  // A small "Listado" badge for a player currently up for transfer.
  function listedBadge(player: PlayerResponse) {
    if (!player.transferListed) return null
    return <Chip size="small" color="warning" label="Listado" sx={{ ml: 0.75, height: 20 }} />
  }

  // The squad-selection button: a starter is Criciúma yellow with their slot label
  // (GK/DC/MC/AT); a substitute is grey ("Sub N"); an unpicked player is white with a "+".
  function statusButton(player: PlayerResponse) {
    const inv = involvementOf(player.id)
    const base = { minWidth: 60, px: 1, fontWeight: 800 }
    if (inv.kind === 'TITULAR') {
      return (
        <Button size="small" variant="contained" onClick={() => cyclePlayer(player)} aria-label={`Escalar ${player.name}`}
          sx={{ ...base, bgcolor: CRICIUMA_YELLOW, color: '#111', '&:hover': { bgcolor: '#e3c200' } }}>
          {positionShort(player, POS_ABBR)}
        </Button>
      )
    }
    if (inv.kind === 'RESERVA') {
      return (
        <Button size="small" variant="contained" onClick={() => cyclePlayer(player)} aria-label={`Escalar ${player.name}`}
          sx={{ ...base, bgcolor: CARBON.bg600, color: '#E5E7EB', '&:hover': { bgcolor: CARBON.bg700 } }}>
          {`Sub ${inv.sub}`}
        </Button>
      )
    }
    // Unpicked: dark-outlined button with a "+". Ineligible (suspended/injured) players can't
    // be added, so their button is disabled; a picked player always stays clickable to remove.
    return (
      <Button size="small" variant="outlined" disabled={!player.eligible} onClick={() => cyclePlayer(player)} aria-label={`Escalar ${player.name}`}
        sx={{ ...base, color: '#9CA3AF', borderColor: CARBON.bg600, '&:hover': { bgcolor: 'rgba(247,212,23,0.08)', borderColor: CARBON.gold, color: CARBON.gold } }}>
        +
      </Button>
    )
  }

  function roleSelect(player: PlayerResponse) {
    return (
      <NativeSelect
        value={roles[player.id] ?? DEFAULT_ROLE[player.position]}
        onChange={e => changeRole(player, e.target.value as PlayerRole)}
        inputProps={{ 'aria-label': `Função de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        {ROLES_BY_POSITION[player.position].map(r => (
          <option key={r} value={r}>{ROLE_LABEL[r]}</option>
        ))}
      </NativeSelect>
    )
  }

  function instructionSelect(player: PlayerResponse) {
    return (
      <NativeSelect
        value={instructions[player.id] ?? ''}
        onChange={e => changeInstruction(player, e.target.value as PlayerInstruction | '')}
        inputProps={{ 'aria-label': `Instrução pessoal de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        <option value="">Nenhuma</option>
        {(Object.keys(INSTRUCTION_LABEL) as PlayerInstruction[]).map(i => (
          <option key={i} value={i}>{INSTRUCTION_LABEL[i]}</option>
        ))}
      </NativeSelect>
    )
  }

  function trainingFocusSelect(player: PlayerResponse) {
    const value = trainingAssignments[player.id]?.focus ?? ''
    return (
      <NativeSelect
        value={value}
        onChange={e => changeTrainingFocus(player, e.target.value as TrainableAttribute | '')}
        inputProps={{ 'aria-label': `Foco de treino de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        <option value="">Nenhum</option>
        {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
          <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
        ))}
      </NativeSelect>
    )
  }

  function trainingIntensitySelect(player: PlayerResponse) {
    const value = trainingAssignments[player.id]?.intensity ?? 'NORMAL'
    return (
      <NativeSelect
        value={value}
        onChange={e => changeTrainingIntensity(player, e.target.value as TrainingIntensity)}
        inputProps={{ 'aria-label': `Intensidade de treino de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
          <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
        ))}
      </NativeSelect>
    )
  }

  function renewButton(player: PlayerResponse) {
    return (
      <Button
        size="small"
        color="secondary"
        disabled={renewMut.isPending}
        onClick={() => renewMut.mutate({ playerId: player.id, wage: player.wageDemand, contractYears: 3 })}
      >
        Renovar
      </Button>
    )
  }

  function listingButton(player: PlayerResponse) {
    const busy = listMut.isPending || unlistMut.isPending
    return player.transferListed ? (
      <Button size="small" color="inherit" variant="outlined" disabled={busy} onClick={() => unlistMut.mutate(player.id)}>
        Retirar da lista
      </Button>
    ) : (
      <Button size="small" color="warning" variant="outlined" disabled={busy} onClick={() => listMut.mutate(player.id)}>
        Listar para transferência
      </Button>
    )
  }

  if (isLoading) return <Typography>Carregando elenco…</Typography>
  if (error) return <Typography color="error">Erro ao carregar elenco.</Typography>

  const formationSelect = (
    <FormControl size="small" sx={{ mb: 2, minWidth: 160 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.25 }}>Formação</Typography>
      <NativeSelect value={formation} onChange={e => changeFormation(e.target.value)} inputProps={{ 'aria-label': 'Formação' }}>
        {FORMATIONS.map(f => <option key={f} value={f}>{f}</option>)}
      </NativeSelect>
    </FormControl>
  )

  // Slot occupancy per line, used by the helper line below.
  const lineTotal = (line: 'GK' | 'DEF' | 'MID' | 'ATT') => slots.filter(s => s.line === line).length
  const lineFilled = (line: 'GK' | 'DEF' | 'MID' | 'ATT') =>
    Object.values(slotByPlayerId).filter(i => slots[i]?.line === line).length

  const helperAndAlerts = (
    <>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
        Formação {formation} — Titulares {selected.length}/11 · Banco {bench.filter(Boolean).length}/9 · GOL {lineFilled('GK')}/{lineTotal('GK')} · DEF {lineFilled('DEF')}/{lineTotal('DEF')} · MEI {lineFilled('MID')}/{lineTotal('MID')} · ATA {lineFilled('ATT')}/{lineTotal('ATT')}
      </Typography>
      {players.length > 0 && (
        <Box sx={{ mb: 2 }}>
          <MoodChip morale={teamMorale(players.map(p => p.morale))} prefix="Elenco: " />
        </Box>
      )}
      {validationError && selected.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }}>{validationError}</Alert>
      )}
    </>
  )

  return (
    <Box>
      <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ mb: 2 }}
            aria-label="Seções do elenco">
        <Tab label="Plantel" id="squad-tab-0" aria-controls="squad-tabpanel-0" />
        <Tab label="Táticas de jogo" id="squad-tab-1" aria-controls="squad-tabpanel-1" />
        <Tab label="Treino" id="squad-tab-2" aria-controls="squad-tabpanel-2" />
      </Tabs>

      {/* Panels stay mounted (hidden) so unsaved lineup AND tactics edits survive tab switches. */}
      <Box role="tabpanel" hidden={tab !== 0} id="squad-tabpanel-0" aria-labelledby="squad-tab-0">
            {formationSelect}
            <Stack direction="row" spacing={1} useFlexGap sx={{ mb: 2, flexWrap: 'wrap' }}>
              <Button size="small" variant="outlined" color="secondary" onClick={autoPickBestXI}>
                Selecionar melhores
              </Button>
              <Button size="small" variant="outlined" color="inherit" onClick={clearSelection} disabled={selected.length === 0}>
                Limpar
              </Button>
            </Stack>
            {helperAndAlerts}

            {isMobile ? (
              <Stack spacing={1.5} sx={{ mb: 2 }}>
                {displayPlayers.map(player => (
                  <MobileSquadRow
                    key={player.id}
                    player={player}
                    statusButton={statusButton}
                    playerNameButton={playerNameButton}
                    availabilityBadge={availabilityBadge}
                    listedBadge={listedBadge}
                  />
                ))}
              </Stack>
            ) : (
              <TableContainer component={Paper} variant="outlined" sx={{ mb: 2, borderRadius: 2 }}>
                <Table size="small">
                  <TableHead>
                    <TableRow sx={{
                      '& th': {
                        color: 'text.secondary', textTransform: 'uppercase', fontSize: '0.7rem',
                        letterSpacing: '0.06em', fontWeight: 700, borderBottomColor: CARBON.bg700,
                      },
                    }}>
                      <TableCell align="center" className="sq-sticky-cell" sx={{ position: 'sticky', left: 0, zIndex: 3, bgcolor: 'background.paper' }}>Escalação</TableCell>
                      <TableCell className="sq-sticky-cell" sx={{ position: 'sticky', left: 96, zIndex: 3, bgcolor: 'background.paper', minWidth: 160 }}>Nome</TableCell>
                      <TableCell align="center">Pos</TableCell>
                      <TableCell align="center">OVR</TableCell>
                      <TableCell align="center">Forma</TableCell>
                      <TableCell align="center">Moral</TableCell>
                      <TableCell align="center">Condição</TableCell>
                      <TableCell align="center">Função</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {displayPlayers.map(player => {
                      const inv = involvementOf(player.id)
                      return (
                        <TableRow key={player.id}>
                          <TableCell align="center" className="sq-sticky-cell" sx={{ position: 'sticky', left: 0, zIndex: 2, bgcolor: 'background.paper' }}>
                            {statusButton(player)}
                          </TableCell>
                          <TableCell className="sq-sticky-cell" sx={{ position: 'sticky', left: 96, zIndex: 2, bgcolor: 'background.paper' }}>
                            {playerNameButton(player)}
                            {availabilityBadge(player)}
                            {listedBadge(player)}
                          </TableCell>
                          <TableCell align="center">{positionLabel(player)}</TableCell>
                          <TableCell align="center">
                            <Box component="span" sx={{ display: 'inline-block', minWidth: 30, px: 1, py: 0.25, borderRadius: 1, ...ovrBadgeSx(player.overall) }}>
                              {player.overall}
                            </Box>
                          </TableCell>
                          <TableCell align="center">{formLabel(player.form)}</TableCell>
                          <TableCell align="center"><MoodChip morale={player.morale} emojiOnly /></TableCell>
                          <TableCell align="center"><FitnessBar fitness={player.fitness} /></TableCell>
                          <TableCell align="center">{inv.kind === 'TITULAR' && roleSelect(player)}</TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}

            <Button
              color="secondary"
              disabled={!canSave || mutation.isPending}
              onClick={() => mutation.mutate()}
            >
              {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
            </Button>
      </Box>

      <Box role="tabpanel" hidden={tab !== 1} id="squad-tabpanel-1" aria-labelledby="squad-tab-1">
        <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, gap: 2, alignItems: 'flex-start' }}>
          <Box sx={{ width: { md: 380 }, flexShrink: 0 }}>
            <Typography sx={{ fontWeight: 700, fontSize: '1.1rem', mb: 1.5 }}>Instruções da equipe</Typography>
            <TacticsPanel
              saveGameId={saveGameId}
              clubId={clubId}
              starters={selected.map(id => {
                const p = players.find(pp => pp.id === id)!
                return { id, name: p.name }
              })}
            />
          </Box>
          {pitchTeam && (
            <Box sx={{ flex: 1, minWidth: 0, mb: 2 }}>
              <Pitch home={pitchTeam} onSwap={swapSlots} />
              <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
                <Button
                  color="secondary"
                  disabled={!canSave || mutation.isPending}
                  onClick={() => mutation.mutate()}
                >
                  {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
                </Button>
              </Box>
            </Box>
          )}
        </Box>
      </Box>

      <Box role="tabpanel" hidden={tab !== 2} id="squad-tabpanel-2" aria-labelledby="squad-tab-2">
        {/* Unlike the Plantel/Táticas panels above, this content is only mounted while the tab
            is active rather than kept mounted-but-hidden: `trainingAssignments` lives in this
            component's own state (not in any uncontrolled child state), so nothing is lost by
            unmounting on tab-away. Mounting it unconditionally would render every player's name
            a second time into the DOM at all times (this table has no selection gate the way the
            Táticas tab's pitch/taker-selects do), breaking every other test that waits on a
            player's name via `getByText`. */}
        {tab === 2 && (
          <>
            <TableContainer component={Paper} variant="outlined" sx={{ mb: 2, borderRadius: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Nome</TableCell>
                    <TableCell align="center">Pos</TableCell>
                    <TableCell align="center">Foco</TableCell>
                    <TableCell align="center">Intensidade</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {displayPlayers.map(player => (
                    <TableRow key={player.id}>
                      <TableCell>{playerNameButton(player)}</TableCell>
                      <TableCell align="center">{positionLabel(player)}</TableCell>
                      <TableCell align="center">{trainingFocusSelect(player)}</TableCell>
                      <TableCell align="center">{trainingIntensitySelect(player)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <Button
              color="secondary"
              disabled={trainingMutation.isPending}
              onClick={() => trainingMutation.mutate()}
            >
              {trainingMutation.isPending ? 'Salvando…' : 'Salvar treino'}
            </Button>
          </>
        )}
      </Box>

      <PlayerDetails
        player={detailsPlayer}
        onClose={() => setDetailsPlayerId(null)}
        saveGameId={saveGameId}
        lineupControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id)
          ? roleSelect(detailsPlayer)
          : undefined}
        instructionControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id) && detailsPlayer.position !== 'GK'
          ? instructionSelect(detailsPlayer)
          : undefined}
        contractAction={detailsPlayer ? renewButton(detailsPlayer) : undefined}
        listingAction={detailsPlayer ? listingButton(detailsPlayer) : undefined}
      />

      {/* Save outcome shown as a transient toast rather than pinned into the layout. */}
      <Snackbar
        open={saveSuccess || mutation.isError}
        autoHideDuration={4000}
        onClose={() => { setSaveSuccess(false); mutation.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={mutation.isError ? 'error' : 'success'}
          variant="filled"
          onClose={() => { setSaveSuccess(false); mutation.reset() }}
          sx={{ width: '100%' }}
        >
          {mutation.isError ? 'Erro ao salvar escalação.' : 'Escalação salva com sucesso!'}
        </Alert>
      </Snackbar>

      {/* Training save outcome, shown the same way as the lineup save toast above. */}
      <Snackbar
        open={trainingSaveSuccess || trainingMutation.isError}
        autoHideDuration={4000}
        onClose={() => { setTrainingSaveSuccess(false); trainingMutation.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={trainingMutation.isError ? 'error' : 'success'}
          variant="filled"
          onClose={() => { setTrainingSaveSuccess(false); trainingMutation.reset() }}
          sx={{ width: '100%' }}
        >
          {trainingMutation.isError ? 'Erro ao salvar treino.' : 'Treino salvo com sucesso!'}
        </Alert>
      </Snackbar>

      {/* Listing/unlisting outcome, shown the same way as the lineup save toast above. */}
      <Snackbar
        open={listMut.isError || unlistMut.isError}
        autoHideDuration={4000}
        onClose={() => { listMut.reset(); unlistMut.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity="error"
          variant="filled"
          onClose={() => { listMut.reset(); unlistMut.reset() }}
          sx={{ width: '100%' }}
        >
          {errorMessage(listMut.error ?? unlistMut.error, 'Erro ao atualizar a lista de transferências.')}
        </Alert>
      </Snackbar>
    </Box>
  )
}

/**
 * Mobile row: name-only summary line (never overflows) + a tap-to-expand detail line with
 * position/OVR/fitness/badges — avoids the name spilling over the fixed-width chips on narrow
 * screens. Defined at module scope (like `TablePage.tsx`'s `MobileStandingRow`) rather than
 * nested inside `SquadPage`, so its identity — and therefore each row's own `open` state — stays
 * stable across `SquadPage` re-renders; the per-player render helpers it needs are passed in as
 * props since they close over `SquadPage`'s local state.
 */
function MobileSquadRow({ player, statusButton, playerNameButton, availabilityBadge, listedBadge }: {
  player: PlayerResponse
  statusButton: (player: PlayerResponse) => ReactNode
  playerNameButton: (player: PlayerResponse) => ReactNode
  availabilityBadge: (player: PlayerResponse) => ReactNode
  listedBadge: (player: PlayerResponse) => ReactNode
}) {
  const [open, setOpen] = useState(false)
  return (
    <Card variant="outlined">
      <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          {statusButton(player)}
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', minWidth: 0 }}>
              <Typography
                component="div"
                sx={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
              >
                {playerNameButton(player)}
              </Typography>
            </Box>
          </Box>
          <IconButton
            size="small"
            onClick={() => setOpen(o => !o)}
            aria-label={`${open ? 'Ocultar' : 'Ver'} detalhes de ${player.name}`}
          >
            {open ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
          </IconButton>
        </Stack>
        <Collapse in={open} timeout="auto" unmountOnExit>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1, flexWrap: 'wrap' }}>
            {availabilityBadge(player)}
            {listedBadge(player)}
            <Chip size="small" label={positionLabel(player)} />
            <Chip size="small" label={`OVR ${player.overall}`} sx={ovrBadgeSx(player.overall)} />
            <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
          </Stack>
        </Collapse>
      </CardContent>
    </Card>
  )
}
