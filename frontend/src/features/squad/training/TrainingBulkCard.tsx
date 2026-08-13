import { useState } from 'react'
import {
  Box, Button, Card, CardContent, Collapse, IconButton, NativeSelect, Stack, Typography,
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import CloseIcon from '@mui/icons-material/Close'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import { DETAILED_POSITION_SHORT } from '../../players/positionLabels'
import type { DetailedPosition, TrainableAttribute, TrainingIntensity } from '../../../api/types'
import { TRAINABLE_ATTRIBUTE_LABEL, INTENSITY_LABEL } from './trainingLabels'
import type { BulkTrainingRule } from './bulkTraining'

const ALL_POSITIONS = Object.keys(DETAILED_POSITION_SHORT) as DetailedPosition[]

/**
 * Sets training focus and intensity for a whole position at once — 26 dropdowns of busywork
 * collapsed into one rule per position.
 *
 * Rules are the caller's state and are deliberately NOT persisted: the card is a one-shot tool
 * whose OUTPUT (each player's own assignment) is what gets saved.
 */
export function TrainingBulkCard({ rules, onRulesChange, onApply }: {
  rules: BulkTrainingRule[]
  onRulesChange: (next: BulkTrainingRule[]) => void
  /** Apply the rules to the squad and save. The caller owns the optimistic update. */
  onApply: () => void
}) {
  const [open, setOpen] = useState(false)

  const used = new Set(rules.map(r => r.position))
  const firstUnused = ALL_POSITIONS.find(p => !used.has(p))

  function addRule() {
    if (!firstUnused) return
    onRulesChange([...rules, { position: firstUnused, focus: null, intensity: 'NORMAL' }])
  }

  function updateRule(index: number, patch: Partial<BulkTrainingRule>) {
    onRulesChange(rules.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  }

  function removeRule(index: number) {
    onRulesChange(rules.filter((_, i) => i !== index))
  }

  return (
    <Card variant="outlined" sx={{ mb: 2, borderRadius: 2 }}>
      <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography sx={{ fontWeight: 700 }}>Treino por posição</Typography>
          <IconButton size="small" onClick={() => setOpen(o => !o)} aria-label="Treino por posição">
            {open ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
          </IconButton>
        </Stack>

        {/* unmountOnExit keeps a collapsed card out of the DOM entirely — the rules themselves
            are the caller's state, so nothing is lost by unmounting the controls. */}
        <Collapse in={open} timeout="auto" unmountOnExit>
          <Stack spacing={1.5} sx={{ mt: 1.5 }}>
            {rules.map((rule, index) => (
              <Stack key={rule.position} direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <NativeSelect
                  value={rule.position}
                  onChange={e => updateRule(index, { position: e.target.value as DetailedPosition })}
                  inputProps={{ 'aria-label': `Posição da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 90 }}
                >
                  {/* Its own position stays selectable; positions claimed by OTHER rules do not,
                      which is what keeps the "one rule per position" invariant. */}
                  {ALL_POSITIONS.filter(p => p === rule.position || !used.has(p)).map(p => (
                    <option key={p} value={p}>{DETAILED_POSITION_SHORT[p]}</option>
                  ))}
                </NativeSelect>
                <NativeSelect
                  value={rule.focus ?? ''}
                  onChange={e => updateRule(index, {
                    focus: e.target.value === '' ? null : (e.target.value as TrainableAttribute),
                  })}
                  inputProps={{ 'aria-label': `Foco da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 140 }}
                >
                  <option value="">Nenhum</option>
                  {(Object.keys(TRAINABLE_ATTRIBUTE_LABEL) as TrainableAttribute[]).map(a => (
                    <option key={a} value={a}>{TRAINABLE_ATTRIBUTE_LABEL[a]}</option>
                  ))}
                </NativeSelect>
                <NativeSelect
                  value={rule.intensity}
                  onChange={e => updateRule(index, { intensity: e.target.value as TrainingIntensity })}
                  inputProps={{ 'aria-label': `Intensidade da regra ${index + 1}` }}
                  sx={{ fontSize: '0.85rem', minWidth: 110 }}
                >
                  {(Object.keys(INTENSITY_LABEL) as TrainingIntensity[]).map(i => (
                    <option key={i} value={i}>{INTENSITY_LABEL[i]}</option>
                  ))}
                </NativeSelect>
                <IconButton size="small" onClick={() => removeRule(index)} aria-label={`Remover regra ${index + 1}`}>
                  <CloseIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}

            <Box>
              <IconButton size="small" onClick={addRule} disabled={!firstUnused} aria-label="Adicionar posição">
                <AddIcon fontSize="small" />
              </IconButton>
            </Box>

            <Box>
              <Button size="small" color="secondary" variant="contained" disabled={rules.length === 0} onClick={onApply}>
                Salvar
              </Button>
            </Box>
          </Stack>
        </Collapse>
      </CardContent>
    </Card>
  )
}
