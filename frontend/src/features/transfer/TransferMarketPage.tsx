import { useState, type CSSProperties } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  getSaveGame, getMarket, getOffers, makeOffer, acceptCounter, withdrawOffer, respondToOffer,
} from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import { isTransferWindowOpen } from '../../lib/transferWindow'

const labelStyle: CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'flex-start', fontSize: '0.72rem', color: '#555',
}

// A labeled money input: visible caption, an "R$" prefix, and a live formatted preview of
// the typed value (or the suggested amount as a hint when empty). The `ariaLabel` stays the
// input's accessible name so existing queries/tests keep working.
function MoneyField(props: {
  label: string
  ariaLabel: string
  value: string
  suggested: number
  onChange: (v: string) => void
}) {
  const n = Number(props.value)
  const preview = props.value !== '' && !Number.isNaN(n) && n > 0
    ? formatBRL(n)
    : `sugerido: ${formatBRL(props.suggested)}`
  return (
    <span style={labelStyle}>
      <span>{props.label}</span>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
        <span aria-hidden>R$</span>
        <input
          aria-label={props.ariaLabel}
          type="number"
          min={0}
          value={props.value}
          onChange={e => props.onChange(e.target.value)}
          style={{ width: 130 }}
        />
      </span>
      <span style={{ color: '#888' }}>{preview}</span>
    </span>
  )
}

export function TransferMarketPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const [amounts, setAmounts] = useState<Record<string, string>>({})
  const [wages, setWages] = useState<Record<string, string>>({})
  const [years, setYears] = useState<Record<string, string>>({})

  const enabled = Boolean(saveGameId && token)
  const save = useQuery({ queryKey: ['saveGame', saveGameId], queryFn: () => getSaveGame(saveGameId, token ?? ''), enabled })
  const market = useQuery({ queryKey: ['market', saveGameId], queryFn: () => getMarket(saveGameId, token ?? ''), enabled })
  const offers = useQuery({ queryKey: ['offers', saveGameId], queryFn: () => getOffers(saveGameId, token ?? ''), enabled })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['market', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['offers', saveGameId] })
  }
  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; amount: number; wage: number; contractYears: number }) =>
      makeOffer(saveGameId, v, token ?? ''),
    onSuccess: invalidate,
  })
  const acceptMut = useMutation({ mutationFn: (id: string) => acceptCounter(saveGameId, id, token ?? ''), onSuccess: invalidate })
  const withdrawMut = useMutation({ mutationFn: (id: string) => withdrawOffer(saveGameId, id, token ?? ''), onSuccess: invalidate })

  const [counters, setCounters] = useState<Record<string, string>>({})
  const respondMut = useMutation({
    mutationFn: (v: { offerId: string; action: 'ACCEPT' | 'REJECT' | 'COUNTER'; counterAmount: number | null }) =>
      respondToOffer(saveGameId, v.offerId, { action: v.action, counterAmount: v.counterAmount }, token ?? ''),
    onSuccess: invalidate,
  })

  if (market.isLoading) return <p>Carregando mercado…</p>
  if (market.error) return <p>Erro ao carregar o mercado.</p>

  const windowOpen = save.data ? isTransferWindowOpen(save.data.currentRound, save.data.totalRounds) : true
  const outgoing = (offers.data ?? []).filter(o => o.direction === 'OUTGOING' && o.status === 'COUNTERED')
  const incoming = (offers.data ?? []).filter(o => o.direction === 'INCOMING' && o.status === 'PENDING')
  const last = offerMut.data

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Mercado de Transferências</h2>
      {!windowOpen && <p role="alert" style={{ color: 'red' }}>Janela de transferências fechada.</p>}
      {last && (
        <p style={{ color: last.status === 'REJECTED' ? 'red' : 'green' }}>
          {last.playerName}: {last.status === 'ACCEPTED' ? 'Proposta aceita!'
            : last.status === 'COUNTERED' ? `Contraproposta: ${formatBRL(last.counterAmount ?? 0)}`
            : 'Proposta recusada.'}
        </p>
      )}
      {offerMut.isError && <p style={{ color: 'red' }}>Erro ao enviar proposta.</p>}

      {incoming.length > 0 && (
        <div style={{ marginBottom: '1rem' }}>
          <h3>Propostas recebidas</h3>
          <ul>
            {incoming.map(o => (
              <li key={o.id} style={{ marginBottom: '0.5rem' }}>
                {o.playerName} — {o.fromClubName} oferece {formatBRL(o.amount)}{' '}
                <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'ACCEPT', counterAmount: null })}>Aceitar venda</button>{' '}
                <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                  onClick={() => respondMut.mutate({ offerId: o.id, action: 'REJECT', counterAmount: null })}>Recusar</button>{' '}
                <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 6, marginLeft: 8, verticalAlign: 'middle' }}>
                  <MoneyField
                    label="Contraproposta"
                    ariaLabel={`Contraproposta para ${o.playerName}`}
                    value={counters[o.id] ?? ''}
                    suggested={o.amount}
                    onChange={v => setCounters(c => ({ ...c, [o.id]: v }))}
                  />
                  <button className="cec-btn" disabled={!windowOpen || respondMut.isPending}
                    onClick={() => respondMut.mutate({ offerId: o.id, action: 'COUNTER', counterAmount: Number(counters[o.id] ?? 0) })}>Contrapor</button>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {outgoing.length > 0 && (
        <div style={{ marginBottom: '1rem' }}>
          <h3>Contrapropostas recebidas</h3>
          <ul>
            {outgoing.map(o => (
              <li key={o.id}>
                {o.playerName} — {formatBRL(o.counterAmount ?? 0)}{' '}
                <button className="cec-btn" disabled={!windowOpen || acceptMut.isPending}
                  onClick={() => acceptMut.mutate(o.id)}>Aceitar</button>{' '}
                <button className="cec-btn" disabled={withdrawMut.isPending}
                  onClick={() => withdrawMut.mutate(o.id)}>Desistir</button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>Nome</th>
            <th>Pos</th><th>Idade</th><th>OVR</th>
            <th style={{ textAlign: 'left' }}>Clube</th>
            <th style={{ textAlign: 'right' }}>Valor</th>
            <th style={{ textAlign: 'right' }}>Salário pedido</th>
            <th>Proposta</th>
          </tr>
        </thead>
        <tbody>
          {(market.data ?? []).map(p => (
            <tr key={p.playerId}>
              <td>{p.name}</td>
              <td style={{ textAlign: 'center' }}>{p.position}</td>
              <td style={{ textAlign: 'center' }}>{p.age}</td>
              <td style={{ textAlign: 'center' }}>{p.overall}</td>
              <td>{p.clubName}</td>
              <td style={{ textAlign: 'right' }}>{formatBRL(p.value)}</td>
              <td style={{ textAlign: 'right' }}>{formatBRL(p.wageDemand)}</td>
              <td style={{ textAlign: 'left' }}>
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 10 }}>
                  <MoneyField
                    label="Valor da proposta"
                    ariaLabel={`Valor para ${p.name}`}
                    value={amounts[p.playerId] ?? ''}
                    suggested={p.value}
                    onChange={v => setAmounts(a => ({ ...a, [p.playerId]: v }))}
                  />
                  <MoneyField
                    label="Salário semanal"
                    ariaLabel={`Salário para ${p.name}`}
                    value={wages[p.playerId] ?? ''}
                    suggested={p.wageDemand}
                    onChange={v => setWages(w => ({ ...w, [p.playerId]: v }))}
                  />
                  <span style={labelStyle}>
                    <span>Anos de contrato</span>
                    <input aria-label={`Anos para ${p.name}`} type="number" min={1} max={5}
                      value={years[p.playerId] ?? '3'}
                      onChange={e => setYears(y => ({ ...y, [p.playerId]: e.target.value }))}
                      style={{ width: 56 }} />
                  </span>
                  <button
                    className="cec-btn"
                    disabled={!windowOpen || offerMut.isPending}
                    onClick={() => offerMut.mutate({
                      playerId: p.playerId,
                      amount: Number(amounts[p.playerId] ?? 0),
                      wage: Number(wages[p.playerId] ?? p.wageDemand),
                      contractYears: Number(years[p.playerId] ?? 3),
                    })}
                  >
                    Propor
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
