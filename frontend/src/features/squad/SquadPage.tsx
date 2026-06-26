import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSquad, setLineup } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { PlayerResponse } from '../../api/types'

const FORMATIONS = ['4-4-2', '4-3-3', '3-5-2', '4-2-3-1', '5-3-2']

function positionLabel(pos: PlayerResponse['position']): string {
  const map: Record<PlayerResponse['position'], string> = {
    GK: 'GK',
    DEF: 'DEF',
    MID: 'MEI',
    ATT: 'ATA',
  }
  return map[pos]
}

function validate(selected: string[], players: PlayerResponse[]): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const gkCount = players.filter(p => selected.includes(p.id) && p.position === 'GK').length
  if (gkCount === 0) {
    return 'É necessário incluir pelo menos 1 goleiro na escalação'
  }
  return null
}

export function SquadPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()

  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const [formation, setFormation] = useState('4-3-3')
  const [selected, setSelected] = useState<string[]>([])
  const [saveSuccess, setSaveSuccess] = useState(false)

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  const mutation = useMutation({
    mutationFn: () =>
      setLineup(saveGameId, clubId, { formation, startingPlayerIds: selected }, token ?? ''),
    onSuccess: () => {
      setSaveSuccess(true)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
    },
  })

  const players = data?.players ?? []
  const validationError = players.length > 0 ? validate(selected, players) : null
  const canSave = validationError === null

  function togglePlayer(id: string) {
    setSaveSuccess(false)
    setSelected(prev => {
      if (prev.includes(id)) {
        return prev.filter(p => p !== id)
      }
      if (prev.length >= 11) return prev
      return [...prev, id]
    })
  }

  if (isLoading) return <p>Carregando elenco…</p>
  if (error) return <p>Erro ao carregar elenco.</p>

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Escalação</h2>

      <div style={{ marginBottom: '1rem' }}>
        <label htmlFor="formation-select">Formação: </label>
        <select
          id="formation-select"
          value={formation}
          onChange={e => setFormation(e.target.value)}
        >
          {FORMATIONS.map(f => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </div>

      <p style={{ fontSize: '0.875rem', color: '#888' }}>
        Selecione 11 jogadores (1 goleiro obrigatório). Selecionados: {selected.length}/11
      </p>

      {validationError && selected.length > 0 && (
        <p role="alert" style={{ color: 'red' }}>
          {validationError}
        </p>
      )}

      {saveSuccess && (
        <p style={{ color: 'green' }}>Escalação salva com sucesso!</p>
      )}

      {mutation.isError && (
        <p style={{ color: 'red' }}>Erro ao salvar escalação.</p>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th></th>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th>
            <th>OVR</th>
            <th>Idade</th>
          </tr>
        </thead>
        <tbody>
          {players.map(player => {
            const isSelected = selected.includes(player.id)
            return (
              <tr
                key={player.id}
                style={{ background: isSelected ? 'rgba(0,128,0,0.08)' : undefined }}
              >
                <td>
                  <input
                    type="checkbox"
                    aria-label={player.name}
                    checked={isSelected}
                    onChange={() => togglePlayer(player.id)}
                  />
                </td>
                <td>{player.name}</td>
                <td style={{ textAlign: 'center' }}>{positionLabel(player.position)}</td>
                <td style={{ textAlign: 'center' }}>{player.overall}</td>
                <td style={{ textAlign: 'center' }}>{player.age}</td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <button
        className="cec-btn"
        disabled={!canSave || mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending ? 'Salvando…' : 'Salvar escalação'}
      </button>
    </div>
  )
}
