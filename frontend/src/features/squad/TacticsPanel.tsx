import { useEffect, useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getTactics, setTactics } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { Mentality, Pressing, Marking } from '../../api/types'

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

export function TacticsPanel({ saveGameId, clubId, starters }: TacticsPanelProps) {
  const { token } = useAuth()
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
    onSuccess: () => setSaved(true),
  })

  function takerSelect(label: string, value: string, onChange: (v: string) => void) {
    return (
      <div>
        <label>
          {label}:{' '}
          <select
            aria-label={label}
            value={value}
            onChange={e => {
              setSaved(false)
              onChange(e.target.value)
            }}
          >
            <option value="">—</option>
            {starters.map(s => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </label>
      </div>
    )
  }

  return (
    <div style={{ marginTop: '1.5rem' }}>
      <h3>Tática</h3>
      <div>
        <label>
          Mentalidade:{' '}
          <select
            aria-label="Mentalidade"
            value={mentality}
            onChange={e => { setSaved(false); setMentality(e.target.value as Mentality) }}
          >
            {MENTALITIES.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>
      <div>
        <label>
          Pressão:{' '}
          <select
            aria-label="Pressão"
            value={pressing}
            onChange={e => { setSaved(false); setPressing(e.target.value as Pressing) }}
          >
            {PRESSINGS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
      </div>
      <div>
        <label>
          Marcação:{' '}
          <select
            aria-label="Marcação"
            value={marking}
            onChange={e => { setSaved(false); setMarking(e.target.value as Marking) }}
          >
            {MARKINGS.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>
      {takerSelect('Pênaltis', penalty, setPenalty)}
      {takerSelect('Faltas', freekick, setFreekick)}
      {takerSelect('Escanteios', corner, setCorner)}

      <button className="cec-btn" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
        {mutation.isPending ? 'Salvando…' : 'Salvar tática'}
      </button>
      {saved && <p style={{ color: 'green' }}>Tática salva!</p>}
      {mutation.isError && (
        <p style={{ color: 'red' }}>Erro ao salvar tática (defina e salve a escalação primeiro).</p>
      )}
    </div>
  )
}
