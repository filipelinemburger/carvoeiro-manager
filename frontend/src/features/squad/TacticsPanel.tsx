import { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Box, Stack, Button, NativeSelect, FormControl, Typography, Alert, Snackbar } from '@mui/material'
import { getTactics, setTactics } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { Mentality, Pressing, Marking } from '../../api/types'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from './tacticsLabels'

interface Starter {
  id: string
  name: string
}

interface TacticsPanelProps {
  saveGameId: string
  clubId: string
  starters: Starter[]
}

const MENTALITIES: Mentality[] = ['DEFENSIVE', 'BALANCED', 'ATTACKING']
const PRESSINGS: Pressing[] = ['LOW', 'MEDIUM', 'HIGH']
const MARKINGS: Marking[] = ['ZONAL', 'MAN_TIGHT']

/**
 * A labeled MUI NativeSelect. Native keeps real <option> elements and an
 * aria-label (via inputProps) so tests can query by label and read options.
 */
function SelectField({ label, value, onChange, children }: {
  label: string
  value: string
  onChange: (v: string) => void
  children: React.ReactNode
}) {
  return (
    <FormControl size="small" fullWidth>
      <Typography variant="caption" sx={{ color: 'text.secondary', mb: 0.25 }}>{label}</Typography>
      <NativeSelect
        value={value}
        onChange={e => onChange(e.target.value)}
        inputProps={{ 'aria-label': label }}
      >
        {children}
      </NativeSelect>
    </FormControl>
  )
}

export function TacticsPanel({ saveGameId, clubId, starters }: TacticsPanelProps) {
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [mentality, setMentality] = useState<Mentality>('BALANCED')
  const [pressing, setPressing] = useState<Pressing>('MEDIUM')
  const [marking, setMarking] = useState<Marking>('ZONAL')
  const [penalty, setPenalty] = useState('')
  const [freekick, setFreekick] = useState('')
  const [corner, setCorner] = useState('')
  const [saved, setSaved] = useState(false)

  const { data } = useQuery({
    queryKey: ['tactics', saveGameId, clubId],
    queryFn: () => getTactics(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
    staleTime: Infinity,
  })

  useEffect(() => {
    if (data) {
      setMentality(data.mentality)
      setPressing(data.pressing)
      setMarking(data.marking)
      setPenalty(data.penaltyTakerId ?? '')
      setFreekick(data.freekickTakerId ?? '')
      setCorner(data.cornerTakerId ?? '')
    }
  }, [data])

  const mutation = useMutation({
    mutationFn: () =>
      setTactics(
        saveGameId,
        clubId,
        {
          mentality,
          pressing,
          marking,
          penaltyTakerId: penalty || null,
          freekickTakerId: freekick || null,
          cornerTakerId: corner || null,
        },
        token ?? '',
      ),
    onSuccess: () => {
      setSaved(true)
      void queryClient.invalidateQueries({ queryKey: ['tactics', saveGameId, clubId] })
    },
  })

  const takerSelect = (label: string, value: string, onChange: (v: string) => void) => (
    <SelectField
      label={label}
      value={value}
      onChange={v => { setSaved(false); onChange(v) }}
    >
      <option value="">—</option>
      {starters.map(s => (
        <option key={s.id} value={s.id}>{s.name}</option>
      ))}
    </SelectField>
  )

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        useFlexGap
        sx={{ flexWrap: 'wrap', mb: 2 }}
      >
        <SelectField label="Mentalidade" value={mentality} onChange={v => { setSaved(false); setMentality(v as Mentality) }}>
          {MENTALITIES.map(m => <option key={m} value={m}>{MENTALITY_LABEL[m]}</option>)}
        </SelectField>
        <SelectField label="Pressão" value={pressing} onChange={v => { setSaved(false); setPressing(v as Pressing) }}>
          {PRESSINGS.map(p => <option key={p} value={p}>{PRESSING_LABEL[p]}</option>)}
        </SelectField>
        <SelectField label="Marcação" value={marking} onChange={v => { setSaved(false); setMarking(v as Marking) }}>
          {MARKINGS.map(m => <option key={m} value={m}>{MARKING_LABEL[m]}</option>)}
        </SelectField>
      </Stack>

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        useFlexGap
        sx={{ flexWrap: 'wrap', mb: 2 }}
      >
        {takerSelect('Pênaltis', penalty, setPenalty)}
        {takerSelect('Faltas', freekick, setFreekick)}
        {takerSelect('Escanteios', corner, setCorner)}
      </Stack>

      <Button color="secondary" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
        {mutation.isPending ? 'Salvando…' : 'Salvar tática'}
      </Button>

      {/* Save outcome shown as a transient toast rather than pinned into the layout. */}
      <Snackbar
        open={saved || mutation.isError}
        autoHideDuration={4000}
        onClose={() => { setSaved(false); mutation.reset() }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={mutation.isError ? 'error' : 'success'}
          variant="filled"
          onClose={() => { setSaved(false); mutation.reset() }}
          sx={{ width: '100%' }}
        >
          {mutation.isError ? 'Erro ao salvar tática (defina e salve a escalação primeiro).' : 'Tática salva!'}
        </Alert>
      </Snackbar>
    </Box>
  )
}
