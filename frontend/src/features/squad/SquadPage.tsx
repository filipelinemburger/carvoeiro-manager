import { useEffect, useRef, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Box, Stack, Typography, Button, Checkbox, Chip, Alert, FormControl, NativeSelect, Link,
  Table, TableHead, TableBody, TableRow, TableCell, TableContainer, Paper, Card, CardContent,
} from '@mui/material'
import { getSquad, getLineup, setLineup, renewContract } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import type { PlayerResponse, PlayerRole, LineupStarter } from '../../api/types'
import { TacticsPanel } from './TacticsPanel'
import { PlayerDetails } from './PlayerDetails'
import { Collapsible } from '../../components/Collapsible'
import { FitnessBar } from './FitnessBar'
import { SEVERITY_LABEL } from './injurySeverity'

const FORMATIONS = ['4-4-2', '4-3-3', '3-5-2', '4-2-3-1', '5-3-2']

type OutfieldPosition = 'DEF' | 'MID' | 'ATT'

// Outfield players required per line for each formation (goalkeeper is always 1).
// Numbers collapse the deeper shapes (e.g. 4-2-3-1 → 4 DEF, 5 MID, 1 ATT) and each
// row sums to 10, so a full XI is exactly 1 GK + these.
const FORMATION_SHAPE: Record<string, Record<OutfieldPosition, number>> = {
  '4-4-2': { DEF: 4, MID: 4, ATT: 2 },
  '4-3-3': { DEF: 4, MID: 3, ATT: 3 },
  '3-5-2': { DEF: 3, MID: 5, ATT: 2 },
  '4-2-3-1': { DEF: 4, MID: 5, ATT: 1 },
  '5-3-2': { DEF: 5, MID: 3, ATT: 2 },
}

const BAND_LABEL: Record<OutfieldPosition, string> = {
  DEF: 'defensor(es)', MID: 'meio-campista(s)', ATT: 'atacante(s)',
}

/** Max players allowed for a position under a formation (goalkeeper is always 1). */
function positionCap(formation: string, position: PlayerResponse['position']): number {
  return position === 'GK' ? 1 : FORMATION_SHAPE[formation][position]
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

function positionLabel(pos: PlayerResponse['position']): string {
  return { GK: 'GK', DEF: 'DEF', MID: 'MEI', ATT: 'ATA' }[pos]
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

function validate(selected: string[], players: PlayerResponse[], formation: string): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const counts = countByPosition(selected, players)
  if (counts.GK !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
  }
  // The line-up must match the chosen formation exactly (e.g. 4-3-3 → 4 DEF, 3 MEI, 3 ATA).
  // This also catches a saved XI that no longer fits after the formation was switched.
  const shape = FORMATION_SHAPE[formation]
  for (const pos of ['DEF', 'MID', 'ATT'] as OutfieldPosition[]) {
    if (counts[pos] !== shape[pos]) {
      return `A formação ${formation} exige ${shape[pos]} ${BAND_LABEL[pos]}, mas você selecionou ${counts[pos]}.`
    }
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
  const [bench, setBench] = useState<string[]>([])
  const [roles, setRoles] = useState<Record<string, PlayerRole>>({})
  const [saveSuccess, setSaveSuccess] = useState(false)
  const [detailsPlayer, setDetailsPlayer] = useState<PlayerResponse | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  const players = data?.players ?? []

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
  })

  // Seed the form from the saved lineup exactly once. A guard prevents later refetches
  // (e.g. the save mutation's invalidation) from clobbering the user's in-progress edits.
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current || !savedLineup.isSuccess) return
    const lineup = savedLineup.data
    if (lineup) {
      setFormation(lineup.formation)
      setSelected(lineup.starters.map(s => s.playerId))
      setRoles(Object.fromEntries(lineup.starters.map(s => [s.playerId, s.role])))
      setBench((lineup.bench ?? []).map(s => s.playerId))
    }
    hydrated.current = true
  }, [savedLineup.isSuccess, savedLineup.data])

  const mutation = useMutation({
    mutationFn: () => {
      const toStarter = (id: string): LineupStarter => {
        const player = players.find(p => p.id === id)!
        return { playerId: id, role: roles[id] ?? DEFAULT_ROLE[player.position] }
      }
      const starters: LineupStarter[] = selected.map(toStarter)
      const benchStarters: LineupStarter[] = bench.map(toStarter)
      return setLineup(saveGameId, clubId, { formation, starters, bench: benchStarters }, token ?? '')
    },
    onSuccess: () => {
      setSaveSuccess(true)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
      // Refresh the layout's lineup-based advance gate: without this the "Escale seu time"
      // warning persists and Avançar stays disabled until a full page reload.
      void queryClient.invalidateQueries({ queryKey: ['lineup', saveGameId] })
      // Re-check the advance gate: fixing the lineup may clear an ineligible-starter block.
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })

  const renewMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      renewContract(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] }) },
  })

  const validationError = players.length > 0 ? validate(selected, players, formation) : null
  const canSave = validationError === null

  // Live per-position tally, used for the formation caps and the progress hint.
  const positionCounts = countByPosition(selected, players)

  // A position band is full once it holds as many players as the formation allows;
  // further additions to that line are blocked.
  function isBandFull(position: PlayerResponse['position']): boolean {
    return positionCounts[position] >= positionCap(formation, position)
  }

  function togglePlayer(player: PlayerResponse) {
    setSaveSuccess(false)
    // Deselect first — always allowed, even for an ineligible player, so the user can
    // remove a starter who became suspended/injured after the lineup was saved. Drop the
    // role too so reselecting starts from the position default.
    if (selected.includes(player.id)) {
      setSelected(prev => prev.filter(p => p !== player.id))
      setRoles(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
      return
    }
    // Adding: an unavailable player may never be added to the XI, and the player's
    // position line must have room under the current formation.
    if (!player.eligible) return
    if (isBandFull(player.position)) return
    // A player entering the XI is dropped from the bench (XI and bench are mutually exclusive).
    setBench(prev => prev.filter(id => id !== player.id))
    setSelected(prev => [...prev, player.id])
    setRoles(prev => ({ ...prev, [player.id]: DEFAULT_ROLE[player.position] }))
  }

  function changeRole(player: PlayerResponse, role: PlayerRole) {
    setRoles(prev => ({ ...prev, [player.id]: role }))
  }

  // Bench: any eligible player not in the XI, up to 9. Removing from the XI also drops
  // them from the bench; selecting a benched player into the XI removes them from the bench.
  function toggleBench(player: PlayerResponse) {
    setSaveSuccess(false)
    if (bench.includes(player.id)) {
      setBench(prev => prev.filter(id => id !== player.id))
      return
    }
    if (!player.eligible || selected.includes(player.id) || bench.length >= 9) return
    setBench(prev => [...prev, player.id])
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
    setBench(prev => prev.filter(id => !pickedIds.includes(id)))
    setRoles(Object.fromEntries(picked.map(p => [p.id, DEFAULT_ROLE[p.position]])))
  }

  // Empties the whole selection.
  function clearSelection() {
    setSaveSuccess(false)
    setSelected([])
    setRoles({})
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
        onClick={() => setDetailsPlayer(player)}
        sx={{ font: 'inherit', fontWeight: 600, textAlign: 'left', verticalAlign: 'baseline', cursor: 'pointer' }}
      >
        {player.name}
      </Link>
    )
  }

  function availabilityBadge(player: PlayerResponse) {
    if (player.suspensionRemaining > 0) {
      return <Typography component="span" color="error" sx={{ ml: 1, fontSize: '0.85em' }}>(Suspenso {player.suspensionRemaining})</Typography>
    }
    if (player.injuryMatches > 0) {
      const tier = SEVERITY_LABEL[player.injurySeverity] ?? ''
      const label = tier ? `(Lesionado (${tier}) ${player.injuryMatches})` : `(Lesionado ${player.injuryMatches})`
      return <Typography component="span" color="error" sx={{ ml: 1, fontSize: '0.85em' }}>{label}</Typography>
    }
    return null
  }

  function playerCheckbox(player: PlayerResponse, isSelected: boolean) {
    return (
      <Checkbox
        size="small"
        checked={isSelected}
        // Block adding when the player is unavailable or their position line is already
        // full for the formation — but a selected starter always stays uncheckable-able
        // so the user can swap them out.
        disabled={!isSelected && (!player.eligible || isBandFull(player.position))}
        onChange={() => togglePlayer(player)}
        slotProps={{ input: { 'aria-label': player.name } }}
        sx={{ p: 0.5 }}
      />
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
          <option key={r} value={r}>{r}</option>
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

  if (isLoading) return <Typography>Carregando elenco…</Typography>
  if (error) return <Typography color="error">Erro ao carregar elenco.</Typography>

  const formationSelect = (
    <FormControl size="small" sx={{ mb: 2, minWidth: 160 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.25 }}>Formação</Typography>
      <NativeSelect value={formation} onChange={e => setFormation(e.target.value)} inputProps={{ 'aria-label': 'Formação' }}>
        {FORMATIONS.map(f => <option key={f} value={f}>{f}</option>)}
      </NativeSelect>
    </FormControl>
  )

  const helperAndAlerts = (
    <>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
        Formação {formation} — {selected.length}/11 · GOL {positionCounts.GK}/1 · DEF {positionCounts.DEF}/{FORMATION_SHAPE[formation].DEF} · MEI {positionCounts.MID}/{FORMATION_SHAPE[formation].MID} · ATA {positionCounts.ATT}/{FORMATION_SHAPE[formation].ATT}
      </Typography>
      {validationError && selected.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }}>{validationError}</Alert>
      )}
      {saveSuccess && <Alert severity="success" sx={{ mb: 2 }}>Escalação salva com sucesso!</Alert>}
      {mutation.isError && <Alert severity="error" sx={{ mb: 2 }}>Erro ao salvar escalação.</Alert>}
    </>
  )

  return (
    <Box>
      <Collapsible title="Escalação">
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
            {displayPlayers.map(player => {
              const isSelected = selected.includes(player.id)
              return (
                <Card key={player.id} variant="outlined" sx={{ bgcolor: isSelected ? '#fff9c4' : undefined }}>
                  <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      {playerCheckbox(player, isSelected)}
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        {playerNameButton(player)}
                        {availabilityBadge(player)}
                      </Box>
                      <Chip size="small" label={positionLabel(player.position)} />
                      <Chip size="small" color="primary" label={`OVR ${player.overall}`} />
                      <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
                    </Stack>
                  </CardContent>
                </Card>
              )
            })}
          </Stack>
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mb: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox" />
                  <TableCell>Nome</TableCell>
                  <TableCell align="center">Pos</TableCell>
                  <TableCell align="center">OVR</TableCell>
                  <TableCell align="center">Forma</TableCell>
                  <TableCell align="center">Fôlego</TableCell>
                  <TableCell align="center">Condição</TableCell>
                  <TableCell align="center">Função</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {displayPlayers.map(player => {
                  const isSelected = selected.includes(player.id)
                  return (
                    <TableRow key={player.id} sx={{ bgcolor: isSelected ? '#fff9c4' : undefined }}>
                      <TableCell padding="checkbox">{playerCheckbox(player, isSelected)}</TableCell>
                      <TableCell>
                        {playerNameButton(player)}
                        {availabilityBadge(player)}
                      </TableCell>
                      <TableCell align="center">{positionLabel(player.position)}</TableCell>
                      <TableCell align="center">{player.overall}</TableCell>
                      <TableCell align="center">{formLabel(player.form)}</TableCell>
                      <TableCell align="center">{player.stamina}</TableCell>
                      <TableCell align="center"><FitnessBar fitness={player.fitness} /></TableCell>
                      <TableCell align="center">{isSelected && roleSelect(player)}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}

        {selected.length === 11 && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>
              Banco de reservas — {bench.length}/9
            </Typography>
            <Stack spacing={1} sx={{ mb: 2 }}>
              {displayPlayers
                .filter(p => !selected.includes(p.id))
                .map(player => {
                  const onBench = bench.includes(player.id)
                  return (
                    <Stack key={player.id} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <Checkbox
                        size="small"
                        checked={onBench}
                        disabled={!onBench && (!player.eligible || bench.length >= 9)}
                        onChange={() => toggleBench(player)}
                        slotProps={{ input: { 'aria-label': `Banco: ${player.name}` } }}
                        sx={{ p: 0.5 }}
                      />
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography component="span" sx={{ fontWeight: 600 }}>{player.name}</Typography>
                        {availabilityBadge(player)}
                      </Box>
                      <Chip size="small" label={positionLabel(player.position)} />
                      <Chip size="small" color="primary" label={`OVR ${player.overall}`} />
                      <Box sx={{ width: 84 }}><FitnessBar fitness={player.fitness} /></Box>
                    </Stack>
                  )
                })}
            </Stack>
          </>
        )}

        <Button
          color="secondary"
          disabled={!canSave || mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
        </Button>
      </Collapsible>

      <Collapsible title="Tática">
        <TacticsPanel
          saveGameId={saveGameId}
          clubId={clubId}
          starters={selected.map(id => {
            const p = players.find(pp => pp.id === id)!
            return { id, name: p.name }
          })}
        />
      </Collapsible>

      <PlayerDetails
        player={detailsPlayer}
        onClose={() => setDetailsPlayer(null)}
        lineupControl={isMobile && detailsPlayer && selected.includes(detailsPlayer.id)
          ? roleSelect(detailsPlayer)
          : undefined}
        contractAction={detailsPlayer ? renewButton(detailsPlayer) : undefined}
      />
    </Box>
  )
}
