import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getSaveGame, getFreeAgents, signFreeAgent } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL, formatThousandsBR, parseMoneyInput } from '../../lib/money'

export function FreeAgentsPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const pool = useQuery({ queryKey: ['freeAgents', saveGameId], queryFn: () => getFreeAgents(saveGameId, token ?? ''), enabled })

  const signMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      signFreeAgent(saveGameId, v.playerId, { wage: v.wage, contractYears: v.contractYears }, token ?? ''),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['freeAgents', saveGameId] })
    },
  })

  if (pool.isLoading) return <p>Carregando agentes livres…</p>
  if (pool.error) return <p>Erro ao carregar agentes livres.</p>

  // Date-based window status from the backend (the single source of truth).
  const windowOpen = save.data?.transferWindowOpen ?? true

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Agentes Livres</h2>
      {!windowOpen && <p role="alert" style={{ color: 'red' }}>Janela de transferências fechada.</p>}
      {signMut.isError && <p style={{ color: 'red' }}>Não foi possível contratar (salário baixo ou elenco cheio).</p>}
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th><th>Idade</th><th>OVR</th>
            <th style={{ textAlign: 'right' }}>Salário pedido</th>
            <th>Contratar</th>
          </tr>
        </thead>
        <tbody>
          {(pool.data ?? []).map(fa => {
            // Default the offer to the demand (ceil so the whole-real value still clears it),
            // displayed with pt-BR grouping so the amount is easy to read at a glance.
            const wageStr = wages[fa.playerId] ?? formatThousandsBR(Math.ceil(fa.wageDemand))
            const offeredWage = parseMoneyInput(wageStr)
            return (
            <tr key={fa.playerId}>
              <td>{fa.name}</td>
              <td style={{ textAlign: 'center' }}>{fa.position}</td>
              <td style={{ textAlign: 'center' }}>{fa.age}</td>
              <td style={{ textAlign: 'center' }}>{fa.overall}</td>
              <td style={{ textAlign: 'right' }}>{formatBRL(fa.wageDemand)}</td>
              <td style={{ textAlign: 'center' }}>
                <span aria-hidden="true" style={{ color: '#666' }}>R$ </span>
                <input aria-label={`Salário para ${fa.name}`} type="text" inputMode="numeric"
                  value={wageStr}
                  onChange={e => {
                    const n = parseMoneyInput(e.target.value)
                    setWages(w => ({ ...w, [fa.playerId]: Number.isNaN(n) ? '' : formatThousandsBR(n) }))
                  }}
                  style={{ width: 110, textAlign: 'right' }} />{' '}
                <input aria-label={`Anos para ${fa.name}`} type="number" min={1} max={5}
                  value={years[fa.playerId] ?? '3'} onChange={e => setYears(y => ({ ...y, [fa.playerId]: e.target.value }))}
                  style={{ width: 50 }} />{' '}
                <button className="cec-btn" disabled={!windowOpen || signMut.isPending}
                  onClick={() => signMut.mutate({
                    playerId: fa.playerId,
                    wage: Number.isNaN(offeredWage) ? Math.ceil(fa.wageDemand) : offeredWage,
                    contractYears: Number(years[fa.playerId] ?? 3),
                  })}>Contratar</button>
              </td>
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
