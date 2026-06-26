import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSquad, setLineup } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import type { PlayerResponse, PlayerRole, LineupStarter } from '../../api/types'

const FORMATIONS = ['4-4-2', '4-3-3', '3-5-2', '4-2-3-1', '5-3-2']

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

function validate(selected: string[], players: PlayerResponse[]): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const gkCount = players.filter(p => selected.includes(p.id) && p.position === 'GK').length
  if (gkCount !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
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
  const [roles, setRoles] = useState<Record<string, PlayerRole>>({})
  const [saveSuccess, setSaveSuccess] = useState(false)

  const { data, isLoading, error } = useQuery({
    queryKey: ['squad', saveGameId, clubId],
    queryFn: () => getSquad(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  const players = data?.players ?? []

  const mutation = useMutation({
    mutationFn: () => {
      const starters: LineupStarter[] = selected.map(id => {
        const player = players.find(p => p.id === id)!
        return { playerId: id, role: roles[id] ?? DEFAULT_ROLE[player.position] }
      })
      return setLineup(saveGameId, clubId, { formation, starters }, token ?? '')
    },
    onSuccess: () => {
      setSaveSuccess(true)
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
    },
  })

  const validationError = players.length > 0 ? validate(selected, players) : null
  const canSave = validationError === null

  function togglePlayer(player: PlayerResponse) {
    if (!player.eligible) return
    setSaveSuccess(false)
    // Deselect: drop both the selection and its role so reselecting starts from
    // the position default and `roles` never accumulates entries for unselected
    // players. Select: add and seed the default role.
    if (selected.includes(player.id)) {
      setSelected(prev => prev.filter(p => p !== player.id))
      setRoles(prev => {
        const next = { ...prev }
        delete next[player.id]
        return next
      })
      return
    }
    if (selected.length >= 11) return
    setSelected(prev => [...prev, player.id])
    setRoles(prev => ({ ...prev, [player.id]: DEFAULT_ROLE[player.position] }))
  }

  function changeRole(player: PlayerResponse, role: PlayerRole) {
    setRoles(prev => ({ ...prev, [player.id]: role }))
  }

  if (isLoading) return <p>Carregando elenco…</p>
  if (error) return <p>Erro ao carregar elenco.</p>

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Escalação</h2>

      <div style={{ marginBottom: '1rem' }}>
        <label htmlFor="formation-select">Formação: </label>
        <select id="formation-select" value={formation} onChange={e => setFormation(e.target.value)}>
          {FORMATIONS.map(f => (
            <option key={f} value={f}>{f}</option>
          ))}
        </select>
      </div>

      <p style={{ fontSize: '0.875rem', color: '#888' }}>
        Selecione 11 jogadores (1 goleiro obrigatório). Selecionados: {selected.length}/11
      </p>

      {validationError && selected.length > 0 && (
        <p role="alert" style={{ color: 'red' }}>{validationError}</p>
      )}
      {saveSuccess && <p style={{ color: 'green' }}>Escalação salva com sucesso!</p>}
      {mutation.isError && <p style={{ color: 'red' }}>Erro ao salvar escalação.</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '1rem' }}>
        <thead>
          <tr>
            <th></th>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th>
            <th>OVR</th>
            <th>Forma</th>
            <th>Fôlego</th>
            <th>Função</th>
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
                    disabled={!player.eligible}
                    onChange={() => togglePlayer(player)}
                  />
                </td>
                <td>
                  {player.name}
                  {!player.eligible && (
                    <span style={{ color: 'red', marginLeft: '0.5rem' }}>
                      (Suspenso {player.suspensionRemaining})
                    </span>
                  )}
                </td>
                <td style={{ textAlign: 'center' }}>{positionLabel(player.position)}</td>
                <td style={{ textAlign: 'center' }}>{player.overall}</td>
                <td style={{ textAlign: 'center' }}>{formLabel(player.form)}</td>
                <td style={{ textAlign: 'center' }}>{player.stamina}</td>
                <td style={{ textAlign: 'center' }}>
                  {isSelected && (
                    <select
                      aria-label={`Função de ${player.name}`}
                      value={roles[player.id] ?? DEFAULT_ROLE[player.position]}
                      onChange={e => changeRole(player, e.target.value as PlayerRole)}
                    >
                      {ROLES_BY_POSITION[player.position].map(r => (
                        <option key={r} value={r}>{r}</option>
                      ))}
                    </select>
                  )}
                </td>
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
