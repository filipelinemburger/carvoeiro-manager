import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getFinances } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import type { FinanceTransaction } from '../../api/types'

const TYPE_LABELS: Record<string, string> = {
  GATE: 'Bilheteria',
  MATCH_COST: 'Custo da partida',
  WAGES: 'Salários',
  SPONSOR: 'Patrocínio',
  UPKEEP: 'Manutenção',
  PRIZE: 'Premiação',
  TRANSFER_IN: 'Venda de jogador',
  TRANSFER_OUT: 'Compra de jogador',
}

/** Returns a YYYY-MM key for grouping: uses txDate if available, falls back to seasonYear/round. */
function monthKey(t: FinanceTransaction): string {
  if (t.txDate) {
    // txDate is 'YYYY-MM-DD' — take the first 7 chars
    return t.txDate.substring(0, 7)
  }
  // Legacy rows without txDate: group by seasonYear (rough fallback)
  return `${t.seasonYear}-??`
}

function monthLabel(key: string): string {
  if (key.includes('??')) return key
  const [year, month] = key.split('-')
  const date = new Date(Number(year), Number(month) - 1, 1)
  return date.toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' })
}

export function FinancesPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['finances', saveGameId, clubId],
    queryFn: () => getFinances(saveGameId, clubId, token ?? ''),
    enabled: Boolean(saveGameId && clubId && token),
  })

  if (isLoading) return <p>Carregando finanças…</p>
  if (error) return <p>Erro ao carregar finanças.</p>

  const balance = data?.balance ?? 0
  const transactions = data?.transactions ?? []
  const negative = balance < 0

  // Group transactions by YYYY-MM (or season fallback), preserving insertion order
  const grouped = new Map<string, FinanceTransaction[]>()
  for (const t of transactions) {
    const key = monthKey(t)
    const group = grouped.get(key)
    if (group) {
      group.push(t)
    } else {
      grouped.set(key, [t])
    }
  }

  return (
    <div style={{ padding: '1.5rem' }}>
      <h2>Finanças</h2>
      <p style={{ fontSize: '1.25rem', fontWeight: 'bold', color: negative ? 'red' : undefined }}>
        Saldo: {formatBRL(balance)}
      </p>
      {negative && (
        <p role="alert" style={{ color: 'red' }}>
          Atenção: o clube está com saldo negativo.
        </p>
      )}

      {grouped.size === 0 && <p>Nenhuma transação ainda.</p>}

      {Array.from(grouped.entries()).map(([key, rows]) => {
        const monthNet = rows.reduce((sum, t) => sum + t.amount, 0)
        return (
          <section key={key} style={{ marginTop: '1.5rem' }}>
            <h3 style={{ borderBottom: '1px solid #ccc', paddingBottom: '0.25rem', marginBottom: '0.5rem', textTransform: 'capitalize' }}>
              {monthLabel(key)}
              <span style={{ fontWeight: 'normal', fontSize: '0.9rem', marginLeft: '1rem', color: monthNet >= 0 ? 'green' : 'red' }}>
                ({monthNet >= 0 ? '+' : ''}{formatBRL(monthNet)})
              </span>
            </h3>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'center' }}>Rodada</th>
                  <th style={{ textAlign: 'left' }}>Tipo</th>
                  <th style={{ textAlign: 'left' }}>Descrição</th>
                  <th style={{ textAlign: 'right' }}>Valor</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((t, i) => (
                  <tr key={`${t.round}-${t.type}-${i}`}>
                    <td style={{ textAlign: 'center' }}>{t.round}</td>
                    <td>{TYPE_LABELS[t.type] ?? t.type}</td>
                    <td>{t.description}</td>
                    <td style={{ textAlign: 'right', color: t.amount < 0 ? 'red' : 'green' }}>
                      {formatBRL(t.amount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )
      })}
    </div>
  )
}
