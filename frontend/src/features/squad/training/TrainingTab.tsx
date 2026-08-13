import { useRef, useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Button, NativeSelect, Paper, Snackbar,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
} from '@mui/material'
import { setTraining } from '../../../api/career'
import { useAuth } from '../../../auth/AuthContext'
import { squadPositionLabel } from '../../players/positionLabels'
import type { PlayerResponse, TrainableAttribute, TrainingAssignment, TrainingIntensity } from '../../../api/types'
import { TRAINABLE_ATTRIBUTE_LABEL, INTENSITY_LABEL } from './trainingLabels'
import { TrainingBulkCard } from './TrainingBulkCard'
import { applyBulkRules, type BulkTrainingRule, type TrainingAssignmentsState } from './bulkTraining'

/**
 * The Treino panel: a per-player focus/intensity table plus its save button.
 *
 * The assignments state itself lives in SquadPage, not here: this panel is mounted only while
 * its tab is active, so state owned here would lose unsaved edits on every tab switch.
 */
export function TrainingTab({ players, saveGameId, clubId, assignments, onChange, playerNameButton }: {
  /** Already display-ordered (GK → DEF → MEI → ATA). */
  players: PlayerResponse[]
  saveGameId: string
  clubId: string
  assignments: TrainingAssignmentsState
  onChange: (next: TrainingAssignmentsState) => void
  playerNameButton: (player: PlayerResponse) => ReactNode
}) {
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [saveSuccess, setSaveSuccess] = useState(false)

  // Bulk rules are one-shot page state: configure, apply, done. Nothing persists them.
  const [rules, setRules] = useState<BulkTrainingRule[]>([])
  // The assignments to restore if an OPTIMISTIC (bulk) save fails. Null for a plain save, which
  // changes nothing up front and therefore has nothing to undo.
  const rollback = useRef<TrainingAssignmentsState | null>(null)

  // The state to send is passed as the mutation variable rather than read from `assignments`,
  // so a save fired in the same tick as an optimistic change sends the NEW values.
  const mutation = useMutation({
    mutationFn: (state: TrainingAssignmentsState) => {
      const list: TrainingAssignment[] = players.map(p => ({
        playerId: p.id,
        focus: state[p.id]?.focus ?? null,
        intensity: state[p.id]?.intensity ?? 'NORMAL',
      }))
      return setTraining(saveGameId, clubId, { assignments: list }, token ?? '')
    },
    onSuccess: (data) => {
      setSaveSuccess(true)
      // Write-through: setTraining already returns a SquadResponse — the exact shape of the
      // squad query — so patch the cache directly instead of paying for a refetch.
      queryClient.setQueryData(['squad', saveGameId, clubId], data)
    },
    onError: () => {
      if (rollback.current) {
        onChange(rollback.current)
        rollback.current = null
      }
    },
  })

  // Optimistic: the table updates before the round-trip, and `onError` puts it back.
  function applyRules() {
    const next = applyBulkRules(assignments, players, rules)
    rollback.current = assignments
    onChange(next)
    mutation.mutate(next)
  }

  function changeFocus(player: PlayerResponse, focus: TrainableAttribute | ''): void {
    onChange({
      ...assignments,
      [player.id]: {
        focus: focus === '' ? null : focus,
        intensity: assignments[player.id]?.intensity ?? 'NORMAL',
      },
    })
  }

  function changeIntensity(player: PlayerResponse, intensity: TrainingIntensity): void {
    onChange({
      ...assignments,
      [player.id]: { focus: assignments[player.id]?.focus ?? null, intensity },
    })
  }

  return (
    <>
      <TrainingBulkCard rules={rules} onRulesChange={setRules} onApply={applyRules} />

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
            {players.map(player => (
              <TableRow key={player.id}>
                <TableCell>{playerNameButton(player)}</TableCell>
                <TableCell align="center">{squadPositionLabel(player)}</TableCell>
                <TableCell align="center">
                  <NativeSelect
                    value={assignments[player.id]?.focus ?? ''}
                    onChange={e => changeFocus(player, e.target.value as TrainableAttribute | '')}
                    inputProps={{ 'aria-label': `Foco de treino de ${player.name}` }}
                    sx={{ fontSize: '0.85rem' }}
                  >
                    <option value="">Nenhum</option>
                    {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
                      <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
                    ))}
                  </NativeSelect>
                </TableCell>
                <TableCell align="center">
                  <NativeSelect
                    value={assignments[player.id]?.intensity ?? 'NORMAL'}
                    onChange={e => changeIntensity(player, e.target.value as TrainingIntensity)}
                    inputProps={{ 'aria-label': `Intensidade de treino de ${player.name}` }}
                    sx={{ fontSize: '0.85rem' }}
                  >
                    {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
                      <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
                    ))}
                  </NativeSelect>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Button
        color="secondary"
        disabled={mutation.isPending}
        onClick={() => { rollback.current = null; mutation.mutate(assignments) }}
      >
        {mutation.isPending ? 'Salvando…' : 'Salvar treino'}
      </Button>

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
          {mutation.isError ? 'Erro ao salvar treino.' : 'Treino salvo com sucesso!'}
        </Alert>
      </Snackbar>
    </>
  )
}
