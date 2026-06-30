import { useEffect, useRef, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSquad, getLineup, setLineup, renewContract } from '../../api/career'
import { formatBRL } from '../../lib/money'
import { useAuth } from '../../auth/AuthContext'
import type { PlayerResponse, PlayerRole, LineupStarter } from '../../api/types'
import { TacticsPanel } from './TacticsPanel'
import { Collapsible } from '../../components/Collapsible'

const FORMATIONS = ['4-4-2', '4-3-3', '3-5-2', '4-2-3-1', '5-3-2']

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

function validate(selected: string[], players: PlayerResponse[]): string | null {
  if (selected.length !== 11) {
    return `Selecione exatamente 11 jogadores (${selected.length}/11)`
  }
  const gkCount = players.filter(p => selected.includes(p.id) && p.position === 'GK').length
  if (gkCount !== 1) {
    return 'A escalação precisa de exatamente 1 goleiro'
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
    }
    hydrated.current = true
  }, [savedLineup.isSuccess, savedLineup.data])

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
      // Re-check the advance gate: fixing the lineup may clear an ineligible-starter block.
      void queryClient.invalidateQueries({ queryKey: ['advancePreview', saveGameId] })
    },
  })

  const renewMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      renewContract(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] }) },
  })

  const validationError = players.length > 0 ? validate(selected, players) : null
  const canSave = validationError === null

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
    // Adding: an unavailable player may never be added to the XI.
    if (!player.eligible) return
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
      <Collapsible title="Escalação">
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
              <th>Contrato</th>
              <th>Salário Pedido</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {displayPlayers.map(player => {
              const isSelected = selected.includes(player.id)
              return (
                <tr
                  key={player.id}
                  style={{ background: isSelected ? '#fff9c4' : undefined }}
                >
                  <td>
                  <input
                    type="checkbox"
                    aria-label={player.name}
                    checked={isSelected}
                    // Ineligible players can't be added, but a saved-then-suspended
                    // starter must stay uncheckable-able so the user can swap them out.
                    disabled={!player.eligible && !isSelected}
                    onChange={() => togglePlayer(player)}
                  />
                </td>
                <td>
                  {player.name}
                  {player.suspensionRemaining > 0 && (
                    <span style={{ color: 'red', marginLeft: '0.5rem' }}>
                      (Suspenso {player.suspensionRemaining})
                    </span>
                  )}
                  {player.injuryMatches > 0 && (
                    <span style={{ color: 'red', marginLeft: '0.5rem' }}>
                      (Lesionado {player.injuryMatches})
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
                <td style={{ textAlign: 'center', color: player.contractYears <= 1 ? 'red' : undefined }}>
                  {player.contractYears} ano(s)
                </td>
                <td style={{ textAlign: 'right' }}>{formatBRL(player.wageDemand)}</td>
                <td style={{ textAlign: 'center' }}>
                  <button
                    className="cec-btn"
                    disabled={renewMut.isPending}
                    onClick={() => renewMut.mutate({ playerId: player.id, wage: player.wageDemand, contractYears: 3 })}
                  >
                    Renovar
                  </button>
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
    </div>
  )
}
