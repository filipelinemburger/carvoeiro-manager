import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { getFinances } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'

const TYPE_LABELS: Record<string, string> = {
  GATE: 'Bilheteria',
  WAGES: 'Salários',
  SPONSOR: 'Patrocínio',
  UPKEEP: 'Manutenção',
  PRIZE: 'Premiação',
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

      <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '1rem' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'center' }}>Rodada</th>
            <th style={{ textAlign: 'left' }}>Tipo</th>
            <th style={{ textAlign: 'left' }}>Descrição</th>
            <th style={{ textAlign: 'right' }}>Valor</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((t, i) => (
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
      {transactions.length === 0 && <p>Nenhuma transação ainda.</p>}
    </div>
  )
}
