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
import { getSquad, getLineup, setLineup, renewContract, listPlayer, unlistPlayer, getFinances, terminateContract } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { formatBRL } from '../../lib/money'
import type { PlayerResponse, RoleArchetype, RoleFocus, DetailedPosition, PlayerInstruction, LineupStarter, SquadResponse, TerminationResult } from '../../api/types'
import { TacticsPanel } from './TacticsPanel'
import { PlayerDetails } from './PlayerDetails'
import { TerminateDialog } from './TerminateDialog'
import { FitnessBar } from './FitnessBar'
import { MoodChip } from './MoodChip'
import { teamMorale } from './morale'
import { SEVERITY_LABEL } from './injurySeverity'
import { SlotPickerDialog } from './SlotPickerDialog'
import { RoleContextMenu, type RoleMenuAnchor } from './RoleContextMenu'
import type { TrainingAssignmentsState } from './training/bulkTraining'
import { TrainingTab } from './training/TrainingTab'
import { Pitch, type PlacedPlayer, type TeamOnPitch } from '../pitch/Pitch'
import { FORMATION_CODES, pitchSlots } from '../pitch/pitchSlots'
import { assignSlots, type AssignablePlayer } from '../pitch/slotAssign'
import { DETAILED_POSITION_SHORT, squadPositionLabel } from '../players/positionLabels'
import {
  ARCHETYPE_LABEL, FOCUS_LABEL, ARCHETYPES_FOR_POSITION, FOCUSES_FOR, defaultArchetypeFor,
  parseFamiliarRoles, archetypesByFamiliarity, bestFamiliarArchetypeFor,
} from '../players/roleLabels'
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

// Representative detailed position per coarse band, for a player without one (legacy payloads) —
// feeds defaultArchetypeFor since a single archetype per LINE is no longer valid (e.g. FULLBACK
// isn't valid for a ZAGUEIRO).
const BAND_FALLBACK_POSITION: Record<PlayerResponse['position'], DetailedPosition> = {
  GK: 'GOLEIRO', DEF: 'ZAGUEIRO', MID: 'MEIA_CENTRAL', ATT: 'ATACANTE',
}

function defaultRoleFor(player: PlayerResponse): RoleArchetype {
  return defaultArchetypeFor(player.detailedPosition ?? BAND_FALLBACK_POSITION[player.position])
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

// Criciúma yellow — every starter's selection button.
const CRICIUMA_YELLOW = '#f7d417'

/** Mirrors SquadRules.MIN_SQUAD_SIZE on the backend; the server is still the authority. */
const MIN_SQUAD_SIZE = 14

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
  // The ONLY position rule left. Playing a player out of position — including an outfielder
  // improvised in goal, or no recognised goalkeeper at all — is the manager's call, and the
  // backend accepts it (LineupService validateGkCount permits 0 or 1). A SECOND keeper is not
  // accepted there, so it is still blocked here rather than surfaced as a 422.
  const counts = countByPosition(selected, players)
  if (counts.GK > 1) {
    return 'No máximo 1 goleiro na escalação'
  }
  // Every starter needs a distinct formation slot (drag-and-drop / auto-assign keeps this
  // true; the guard catches degenerate states like a saved starter no longer in the squad).
  // `selected` is filtered down to ids that still resolve to a real player BEFORE the slot
  // lookup: a starter can depart mid-session (e.g. Layout's advance-day refetch invalidates
  // the squad query while this page stays mounted with them already selected) without
  // anything clearing their `selected`/`slotByPlayerId` entries — their slot must not count
  // toward the 11, or `toStarter` crashes on their now-missing player at save time instead.
  const assigned = new Set(
    selected.filter(id => players.some(p => p.id === id))
      .map(id => slotByPlayerId[id]).filter(v => v !== undefined))
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
  const [roles, setRoles] = useState<Record<string, RoleArchetype>>({})
  // The role's focus, keyed by playerId; an absent entry falls back to the archetype's default
  // for the player's (slot-derived) position — see `effectiveFocus`.
  const [focuses, setFocuses] = useState<Record<string, RoleFocus>>({})
  // Per-player instruction keyed by playerId; '' is "Nenhuma" (no instruction). Kept in step with
  // `roles`: every place that seeds or clears a role does the same to the instruction.
  const [instructions, setInstructions] = useState<Record<string, PlayerInstruction | ''>>({})
  const [saveSuccess, setSaveSuccess] = useState(false)
  const [detailsPlayerId, setDetailsPlayerId] = useState<string | null>(null)
  const [terminatingId, setTerminatingId] = useState<string | null>(null)
  /** What the last termination cost, held only long enough to report it in a toast. */
  const [terminationOutcome, setTerminationOutcome] = useState<TerminationResult | null>(null)
  const [tab, setTab] = useState(0)
  const [trainingAssignments, setTrainingAssignments] = useState<TrainingAssignmentsState>({})
  // playerId -> formation slot index (0 = GK). Keys ⊆ selected; values unique. The pitch
  // renders from this map and drag-and-drop edits it.
  const [slotByPlayerId, setSlotByPlayerId] = useState<Record<string, number>>({})
  // The pitch slot index a tap opened the picker for; null when the picker is closed.
  const [pickerSlot, setPickerSlot] = useState<number | null>(null)
  // The player whose Função/Foco menu is open (right-click / long-press on their pitch token),
  // plus the pointer position to anchor it at; null when the menu is closed.
  const [roleMenu, setRoleMenu] = useState<RoleMenuAnchor | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  // Same key FinancesPage already uses and TransferMarketPage already invalidates — the cache
  // is shared, so the balance shown here is never a separate fetch from the Finances page.
  const finances = useQuery({
    queryKey: ['finances', saveGameId, clubId],
    queryFn: () => getFinances(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  const players = data?.players ?? []
  const slots = pitchSlots(formation)

  // altPositions is load-bearing, not decoration: assignSlots counts a player as NATURAL for any
  // of his positions, so dropping the alternates here would make the client improvise players the
  // backend places naturally — auto-pick, formation change and hydration would all disagree with
  // the server's XI, and the pitch (which reads the full payload) would then show no warn tint on
  // the improviser the client itself chose.
  const toAssignable = (p: PlayerResponse): AssignablePlayer => ({
    id: p.id,
    position: p.position,
    detailedPosition: p.detailedPosition,
    altPositions: p.altPositions ?? undefined,
    overall: p.overall,
  })

  // The detailed position Função/Foco are chosen against: a starter's ASSIGNED SLOT position,
  // or a bench/unpicked player's own primary position (falling back to the coarse band's
  // representative position for a legacy payload without one).
  function positionForRole(player: PlayerResponse): DetailedPosition {
    const slotIndex = slotByPlayerId[player.id]
    const slot = slotIndex !== undefined ? slots[slotIndex] : undefined
    return slot ? slot.accepted[0] : (player.detailedPosition ?? BAND_FALLBACK_POSITION[player.position])
  }

  // The role actually shown/saved for a player: their stored archetype if still valid for the
  // position it's being judged against, else that position's vanilla default. Falling back this
  // way (rather than persisting a correction) means a slot change — drag, swap, formation change,
  // or the picker — never leaves a stale archetype invalid for the new position.
  function effectiveArchetype(player: PlayerResponse): RoleArchetype {
    const position = positionForRole(player)
    const stored = roles[player.id]
    return stored && ARCHETYPES_FOR_POSITION[position].includes(stored) ? stored : defaultArchetypeFor(position)
  }

  function effectiveFocus(player: PlayerResponse): RoleFocus {
    const position = positionForRole(player)
    const valid = FOCUSES_FOR(effectiveArchetype(player), position)
    const stored = focuses[player.id]
    return stored && valid.includes(stored) ? stored : valid[0]
  }

  // Tracks the query cache (rather than a snapshot taken at click time) so the open dialog
  // reflects updates — e.g. an optimistic contract renewal — without the user closing/reopening.
  const detailsPlayer = players.find(p => p.id === detailsPlayerId) ?? null
  const roleMenuPlayer = players.find(p => p.id === roleMenu?.playerId) ?? null

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
      setFocuses(Object.fromEntries(
        lineup.starters.filter((s): s is typeof s & { focus: RoleFocus } => Boolean(s.focus))
          .map(s => [s.playerId, s.focus])))
      setInstructions(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.instruction ?? ''])))
      setBench((lineup.bench ?? []).map(s => s.playerId))
      if (lineup.starters.every(s => s.slotIndex != null)) {
        // A saved starter can reference a player who has since left the squad (transferred,
        // released, retired). Drop them here too — the fallback branch below already does the
        // same via its `.filter(Boolean)` — so `validate`'s permutation-size check (which exists
        // specifically to catch "a saved starter no longer in the squad") sees fewer than 11
        // slots and blocks saving, instead of `toStarter` throwing on a ghost id at save time.
        const existing = lineup.starters.filter(s => data.players.some(p => p.id === s.playerId))
        setSlotByPlayerId(Object.fromEntries(
          existing.map(s => [s.playerId, s.slotIndex as number])))
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
          role: effectiveArchetype(player),
          focus: effectiveFocus(player),
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

  const terminateMut = useMutation({
    mutationFn: (playerId: string) => terminateContract(saveGameId, playerId, token ?? ''),
    onSuccess: (data) => {
      setTerminatingId(null)
      setDetailsPlayerId(null)
      // Both dialogs close on success, so the toast is the only place the cost is ever reported.
      setTerminationOutcome(data)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['finances', saveGameId, clubId] })
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
      void queryClient.invalidateQueries({ queryKey: ['lineup', saveGameId, clubId] })
      // releaseFrom unlists the player, so a transfer-listed one leaves a stale market row.
      void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    },
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
        detailedPosition: p.detailedPosition, altPositions: p.altPositions, fitness: p.fitness,
        slotIndex: slotByPlayerId[p.id],
      })),
  } : null

  // First free slot for a newly picked starter: an exact detailed-position slot first, else
  // any free slot of the player's coarse line, else any free slot at all.
  function firstFreeSlotFor(player: PlayerResponse): number | null {
    const taken = new Set(Object.values(slotByPlayerId))
    const free = slots.map((slot, index) => ({ slot, index })).filter(x => !taken.has(x.index))
    const exact = player.detailedPosition
      ? free.find(x => x.slot.accepted.includes(player.detailedPosition!))
      : undefined
    // Preference order: a slot that accepts the player's exact detailed position, then any free
    // slot on their own coarse line, then ANY free slot at all. The last fallback is what lets a
    // defender be pushed into a full-elsewhere XI rather than being bounced to the bench.
    return (exact ?? free.find(x => x.slot.line === player.position) ?? free[0])?.index ?? null
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
      setRoles(prev => ({ ...prev, [player.id]: defaultRoleFor(player) }))
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
      setRoles(prev => ({ ...prev, [player.id]: defaultRoleFor(player) }))
    }
  }

  // Changing Função resets Foco to the new archetype's default for the player's position — an
  // old focus can be meaningless (or simply not offered) for a different archetype.
  function changeArchetype(player: PlayerResponse, archetype: RoleArchetype) {
    setRoles(prev => ({ ...prev, [player.id]: archetype }))
    const position = positionForRole(player)
    setFocuses(prev => ({ ...prev, [player.id]: FOCUSES_FOR(archetype, position)[0] }))
  }

  function changeFocus(player: PlayerResponse, focus: RoleFocus) {
    setFocuses(prev => ({ ...prev, [player.id]: focus }))
  }

  function changeInstruction(player: PlayerResponse, value: PlayerInstruction | '') {
    setInstructions(prev => ({ ...prev, [player.id]: value }))
  }

  // Fills the XI natural-first: `assignSlots` (the same greedy natural-first best-fit used for
  // hydration/formation-change) runs over the WHOLE eligible squad, so each slot gets the
  // highest-rated genuinely natural candidate before falling back to the closest improviser —
  // rather than the old per-line "top N by overall" cut. Each picked player then gets their best
  // FAMILIAR archetype for the slot they land in (falling back to the vanilla default), and that
  // archetype's own default focus.
  function autoPickBestXI() {
    setSaveSuccess(false)
    const eligible = players.filter(p => p.eligible)
    const newSlotByPlayerId = assignSlots(eligible.map(toAssignable), formation)
    const pickedIds = Object.keys(newSlotByPlayerId)
    const picked = pickedIds
      .map(id => players.find(p => p.id === id))
      .filter((p): p is PlayerResponse => Boolean(p))
    setSelected(pickedIds)
    // Any bench player promoted into the XI vacates their bench slot (keeps the rest stable).
    setBench(prev => prev.map(id => (id !== null && pickedIds.includes(id) ? null : id)))
    const newRoles: Record<string, RoleArchetype> = {}
    const newFocuses: Record<string, RoleFocus> = {}
    for (const p of picked) {
      const position = slots[newSlotByPlayerId[p.id]].accepted[0]
      const archetype = bestFamiliarArchetypeFor(p.familiarRoles, position)
      newRoles[p.id] = archetype
      newFocuses[p.id] = FOCUSES_FOR(archetype, position)[0]
    }
    setRoles(newRoles)
    setFocuses(newFocuses)
    // Fresh auto-pick: every picked player starts with no personal instruction.
    setInstructions(Object.fromEntries(picked.map(p => [p.id, ''])))
    setSlotByPlayerId(newSlotByPlayerId)
  }

  // Empties the whole selection.
  function clearSelection() {
    setSaveSuccess(false)
    setSelected([])
    setRoles({})
    setFocuses({})
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

  // Assigns `player` (from the slot picker) to `slotIndex` — writes into `slotByPlayerId` exactly
  // like a drop does. If the slot already holds someone, the two SWAP places rather than the
  // occupant being dropped: a starter elsewhere trades slots; a bench/unpicked player instead
  // hands the occupant their own spot (the bench slot they came from, or the next free bench
  // place — capped at 9 — when the picked player wasn't on the pitch or bench at all).
  function pickForSlot(slotIndex: number, player: PlayerResponse) {
    setSaveSuccess(false)
    const occupantId = Object.keys(slotByPlayerId).find(id => slotByPlayerId[id] === slotIndex)
    if (occupantId === player.id) { setPickerSlot(null); return }

    const pickedSlot = slotByPlayerId[player.id]
    const pickedBenchIndex = bench.indexOf(player.id)

    // The picked player has no "home" of their own (not a starter elsewhere, not on the bench)
    // — so if there's an occupant to displace, their only possible destination is a free bench
    // place. When the bench is already full (no hole, at the 9-slot cap), there's nowhere to put
    // them: bail out entirely rather than perform a partial swap that drops them from the squad
    // — same precedent as `cyclePlayer`'s `benchHasRoom` no-op above.
    if (occupantId && pickedSlot === undefined && pickedBenchIndex < 0) {
      const benchHasRoom = bench.some(id => id === null) || bench.length < 9
      if (!benchHasRoom) { setPickerSlot(null); return }
    }

    const nextSlotByPlayerId = { ...slotByPlayerId, [player.id]: slotIndex }
    let nextSelected = selected.includes(player.id) ? selected : [...selected, player.id]
    const nextBench = [...bench]
    if (pickedBenchIndex >= 0) nextBench[pickedBenchIndex] = null

    if (occupantId) {
      if (pickedSlot !== undefined) {
        // The picked player was a starter elsewhere: a genuine swap of formation slots — neither
        // player touches the bench.
        nextSlotByPlayerId[occupantId] = pickedSlot
      } else {
        // The displaced occupant takes the picked player's previous place: their own bench slot
        // if they came from the bench, else the next free bench place (room guaranteed by the
        // early-exit check above) — never dropped outright.
        delete nextSlotByPlayerId[occupantId]
        nextSelected = nextSelected.filter(id => id !== occupantId)
        if (pickedBenchIndex >= 0) {
          nextBench[pickedBenchIndex] = occupantId
        } else {
          const hole = nextBench.findIndex(id => id === null)
          if (hole >= 0) nextBench[hole] = occupantId
          else nextBench.push(occupantId)
        }
      }
    }

    setSlotByPlayerId(nextSlotByPlayerId)
    setSelected(nextSelected)
    setBench(nextBench)
    setPickerSlot(null)
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

  // The squad-selection button: a starter is Criciúma yellow with the label of the SLOT they
  // occupy; a substitute is grey ("Sub N"); an unpicked player is white with a "+".
  // The slot label — not the player's own position — is what makes an improvised starter
  // legible here: a ZAG fielded at left-back reads "LE" in this column and "ZAG" in Pos, the
  // same mismatch the pitch marks with its amber "Fora de posição" tint.
  function statusButton(player: PlayerResponse) {
    const inv = involvementOf(player.id)
    const base = { minWidth: 60, px: 1, fontWeight: 800 }
    if (inv.kind === 'TITULAR') {
      return (
        <Button size="small" variant="contained" onClick={() => cyclePlayer(player)} aria-label={`Escalar ${player.name}`}
          sx={{ ...base, bgcolor: CRICIUMA_YELLOW, color: '#111', '&:hover': { bgcolor: '#e3c200' } }}>
          {DETAILED_POSITION_SHORT[positionForRole(player)]}
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

  // Função options: the archetypes valid for the player's position (their ASSIGNED SLOT when a
  // starter, else their own primary position), familiar ones first — marked ★★ (fully familiar,
  // a "++" token) or ★ (somewhat, a "+" token).
  function funcaoSelect(player: PlayerResponse) {
    const position = positionForRole(player)
    const familiarity = parseFamiliarRoles(player.familiarRoles)
    const options = archetypesByFamiliarity(position, familiarity)
    return (
      <NativeSelect
        value={effectiveArchetype(player)}
        onChange={e => changeArchetype(player, e.target.value as RoleArchetype)}
        inputProps={{ 'aria-label': `Função de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        {options.map(r => {
          const level = familiarity[r] ?? 0
          const star = level === 2 ? ' ★★' : level === 1 ? ' ★' : ''
          return <option key={r} value={r}>{ARCHETYPE_LABEL[r]}{star}</option>
        })}
      </NativeSelect>
    )
  }

  // Foco options: the focuses valid for the player's current (effective) archetype at that
  // same position, default first.
  function focoSelect(player: PlayerResponse) {
    const position = positionForRole(player)
    const archetype = effectiveArchetype(player)
    const options = FOCUSES_FOR(archetype, position)
    return (
      <NativeSelect
        value={effectiveFocus(player)}
        onChange={e => changeFocus(player, e.target.value as RoleFocus)}
        inputProps={{ 'aria-label': `Foco de ${player.name}` }}
        sx={{ fontSize: '0.85rem' }}
      >
        {options.map(f => <option key={f} value={f}>{FOCUS_LABEL[f]}</option>)}
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

  /** Why this player cannot be released right now, or null when he can be. Explains only —
   * the backend enforces all three rules regardless. */
  function terminationBlockedReason(player: PlayerResponse): string | null {
    const senior = players
    if (senior.length <= MIN_SQUAD_SIZE) {
      return `O elenco ficaria abaixo do mínimo de ${MIN_SQUAD_SIZE} jogadores.`
    }
    if (player.position === 'GK' && senior.filter(p => p.position === 'GK').length <= 1) {
      return 'Este é o seu último goleiro.'
    }
    const balance = finances.data?.balance
    if (balance !== undefined && balance < (player.terminationFee ?? 0)) {
      return 'Saldo insuficiente para a multa.'
    }
    return null
  }

  function terminateButton(player: PlayerResponse) {
    return (
      <Button size="small" color="error" disabled={terminateMut.isPending}
        onClick={() => setTerminatingId(player.id)}>
        Rescindir
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
              <Button
                color="secondary"
                size="small"
                variant="contained"
                disabled={!canSave || mutation.isPending}
                onClick={() => mutation.mutate()}
              >
                {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
              </Button>
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
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {displayPlayers.map(player => (
                      <TableRow key={player.id}>
                        <TableCell align="center" className="sq-sticky-cell" sx={{ position: 'sticky', left: 0, zIndex: 2, bgcolor: 'background.paper' }}>
                          {statusButton(player)}
                        </TableCell>
                        <TableCell className="sq-sticky-cell" sx={{ position: 'sticky', left: 96, zIndex: 2, bgcolor: 'background.paper' }}>
                          {playerNameButton(player)}
                          {availabilityBadge(player)}
                          {listedBadge(player)}
                        </TableCell>
                        <TableCell align="center">{squadPositionLabel(player)}</TableCell>
                        <TableCell align="center">
                          <Box component="span" sx={{ display: 'inline-block', minWidth: 30, px: 1, py: 0.25, borderRadius: 1, ...ovrBadgeSx(player.overall) }}>
                            {player.overall}
                          </Box>
                        </TableCell>
                        <TableCell align="center">{formLabel(player.form)}</TableCell>
                        <TableCell align="center"><MoodChip morale={player.morale} emojiOnly /></TableCell>
                        <TableCell align="center"><FitnessBar fitness={player.fitness} /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
      </Box>

      <Box role="tabpanel" hidden={tab !== 1} id="squad-tabpanel-1" aria-labelledby="squad-tab-1">
        <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, gap: 2, alignItems: 'flex-start' }}>
          <Box sx={{ width: { md: 380 }, flexShrink: 0 }}>
            <Typography sx={{ fontWeight: 700, fontSize: '1.1rem', mb: 1.5 }}>Instruções da equipe</Typography>
            <TacticsPanel
              saveGameId={saveGameId}
              clubId={clubId}
              starters={selected
                .map(id => players.find(pp => pp.id === id))
                // A selected id can outlive its player (e.g. a saved starter who has since left
                // the squad) until validate blocks the save; drop it here rather than crash.
                .filter((p): p is PlayerResponse => Boolean(p))
                .map(p => ({ id: p.id, name: p.name }))}
            />
          </Box>
          {pitchTeam && (
            <Box sx={{ flex: 1, minWidth: 0, mb: 2 }}>
              {tab === 1 && players.length > 0 && (
                <Box sx={{ mb: 1.5, display: 'flex', justifyContent: 'center' }}>
                  <MoodChip morale={teamMorale(players.map(p => p.morale))} prefix="Elenco: " />
                </Box>
              )}
              <Pitch
                home={pitchTeam}
                onSwap={swapSlots}
                onSlotClick={slotIndex => setPickerSlot(slotIndex)}
                onSlotContextMenu={(slotIndex, x, y) => {
                  const playerId = Object.keys(slotByPlayerId).find(id => slotByPlayerId[id] === slotIndex)
                  if (playerId) setRoleMenu({ playerId, x, y })
                }}
              />
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
            is active rather than kept mounted-but-hidden: `trainingAssignments` lives in THIS
            component's state (TrainingTab only reads it), so nothing is lost by unmounting on
            tab-away. Mounting it unconditionally would render every player's name a second time
            into the DOM at all times, breaking every other test that waits on a player's name
            via `getByText`. */}
        {tab === 2 && (
          <TrainingTab
            players={displayPlayers}
            saveGameId={saveGameId}
            clubId={clubId}
            assignments={trainingAssignments}
            onChange={setTrainingAssignments}
            playerNameButton={playerNameButton}
          />
        )}
      </Box>

      <PlayerDetails
        player={detailsPlayer}
        onClose={() => setDetailsPlayerId(null)}
        saveGameId={saveGameId}
        lineupControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id)
          ? (
            <Stack spacing={1}>
              <Box>
                <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Função</Typography>
                {funcaoSelect(detailsPlayer)}
              </Box>
              <Box>
                <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Foco</Typography>
                {focoSelect(detailsPlayer)}
              </Box>
            </Stack>
          )
          : undefined}
        instructionControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id) && detailsPlayer.position !== 'GK'
          ? instructionSelect(detailsPlayer)
          : undefined}
        contractAction={detailsPlayer ? (
          <Stack direction="row" spacing={1}>
            {renewButton(detailsPlayer)}
            {terminateButton(detailsPlayer)}
          </Stack>
        ) : undefined}
        listingAction={detailsPlayer ? listingButton(detailsPlayer) : undefined}
      />

      <TerminateDialog
        player={players.find(p => p.id === terminatingId) ?? null}
        balance={finances.data?.balance}
        blockedReason={(() => {
          const p = players.find(x => x.id === terminatingId)
          return p ? terminationBlockedReason(p) : null
        })()}
        pending={terminateMut.isPending}
        onConfirm={() => { if (terminatingId) terminateMut.mutate(terminatingId) }}
        onClose={() => setTerminatingId(null)}
      />

      {/* Opened by tapping a pitch slot in the Táticas tab (spec §5.1/§5.2). */}
      <SlotPickerDialog
        open={pickerSlot !== null}
        onClose={() => setPickerSlot(null)}
        slot={pickerSlot !== null ? slots[pickerSlot] : null}
        players={players.filter(p => p.eligible)}
        currentOccupantId={pickerSlot !== null
          ? Object.keys(slotByPlayerId).find(id => slotByPlayerId[id] === pickerSlot)
          : undefined}
        onPick={player => { if (pickerSlot !== null) pickForSlot(pickerSlot, player) }}
      />

      {/* Opened by right-clicking / long-pressing a pitch token in the Táticas tab (spec §6.2). */}
      <RoleContextMenu
        anchor={roleMenuPlayer ? roleMenu : null}
        playerName={roleMenuPlayer?.name ?? ''}
        funcaoControl={roleMenuPlayer ? funcaoSelect(roleMenuPlayer) : null}
        focoControl={roleMenuPlayer ? focoSelect(roleMenuPlayer) : null}
        onClose={() => setRoleMenu(null)}
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

      {/* What the release cost, reported once — the balance itself lives on another page. */}
      <Snackbar
        open={terminationOutcome !== null}
        autoHideDuration={6000}
        onClose={() => setTerminationOutcome(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity="success"
          variant="filled"
          onClose={() => setTerminationOutcome(null)}
          sx={{ width: '100%' }}
        >
          {terminationOutcome
            && `Contrato de ${terminationOutcome.playerName} rescindido por `
              + `${formatBRL(terminationOutcome.fee)}. Novo saldo: `
              + `${formatBRL(terminationOutcome.newBalance)}.`}
        </Alert>
      </Snackbar>

      {/* Termination failure, shown the same way as the listing/unlisting toast above. */}
      <Snackbar
        open={terminateMut.isError}
        autoHideDuration={4000}
        onClose={() => terminateMut.reset()}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity="error"
          variant="filled"
          onClose={() => terminateMut.reset()}
          sx={{ width: '100%' }}
        >
          {errorMessage(terminateMut.error, 'Erro ao rescindir o contrato.')}
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
            <Chip size="small" label={squadPositionLabel(player)} />
            <Chip size="small" label={`OVR ${player.overall}`} sx={ovrBadgeSx(player.overall)} />
            <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
          </Stack>
        </Collapse>
      </CardContent>
    </Card>
  )
}
