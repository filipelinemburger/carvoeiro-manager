import { Link } from 'react-router-dom'
import { formatBRL } from '../../lib/money'
import type { AdvanceResponse } from '../../api/types'

interface AdvanceEventCardProps {
  result: AdvanceResponse
  saveGameId: string
  competitionId: string
  managedClubId: string
}

export function formatDatePtBR(isoDate: string | null): string {
  if (!isoDate) return ''
  // Parse the date parts manually to avoid UTC vs local timezone offset issues.
  // ISO date-only strings (yyyy-mm-dd) would otherwise shift a day in some timezones.
  const [year, month, day] = isoDate.split('T')[0].split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString('pt-BR')
}

function TimelineNode({ children, dot = '●' }: { children: React.ReactNode; dot?: string }) {
  return (
    <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '0.5rem' }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: '20px' }}>
        <span style={{ color: 'var(--cec-yellow)', fontSize: '0.9rem', lineHeight: 1 }}>{dot}</span>
        <div style={{ flex: 1, width: '2px', background: '#ddd', marginTop: '4px' }} />
      </div>
      <div style={{ flex: 1, paddingBottom: '0.5rem' }}>{children}</div>
    </div>
  )
}

function MatchTimeline({ result, saveGameId, competitionId, managedClubId }: {
  result: AdvanceResponse
  saveGameId: string
  competitionId: string
  managedClubId: string
}) {
  const seasonSummaryUrl =
    `/season-summary?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`

  return (
    <>
      {[...result.results]
        // Always surface the managed club's match first.
        .sort((a, b) =>
          Number(b.homeClubId === managedClubId || b.awayClubId === managedClubId) -
          Number(a.homeClubId === managedClubId || a.awayClubId === managedClubId))
        .map(r => {
          const mine = r.homeClubId === managedClubId || r.awayClubId === managedClubId
          return (
            <TimelineNode key={r.fixtureId} dot={mine ? '⭐' : '●'}>
              <span
                style={{
                  fontFamily: 'monospace',
                  fontSize: '0.95rem',
                  fontWeight: mine ? 700 : undefined,
                  ...(mine
                    ? { background: 'rgba(247,212,23,0.3)', padding: '2px 6px', borderRadius: '4px', display: 'inline-block' }
                    : {}),
                }}
              >
                {r.homeClubName} <strong>{r.homeGoals}</strong>
                {' — '}
                <strong>{r.awayGoals}</strong> {r.awayClubName}
              </span>
            </TimelineNode>
          )
        })}

      {result.bookings.length > 0 && (
        <TimelineNode dot="🟨">
          <strong>Cartões</strong>
          <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem' }}>
            {result.bookings.map((b, i) => (
              <li key={`${b.playerId}-${i}`}>
                {b.playerName} — {b.type === 'RED_CARD' ? 'Vermelho' : 'Amarelo'}
              </li>
            ))}
          </ul>
        </TimelineNode>
      )}

      {result.newSuspensions.length > 0 && (
        <TimelineNode dot="🚫">
          <strong>Suspensões</strong>
          <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem' }}>
            {result.newSuspensions.map((s, i) => (
              <li key={`${s.playerId}-${i}`}>
                {s.playerName} — {s.matches} jogo(s)
              </li>
            ))}
          </ul>
        </TimelineNode>
      )}

      {result.newInjuries.length > 0 && (
        <TimelineNode dot="🩹">
          <strong>Lesões</strong>
          <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem' }}>
            {result.newInjuries.map((inj, i) => (
              <li key={`${inj.playerId}-${i}`}>
                {inj.playerName} — {inj.matches} jogo(s)
              </li>
            ))}
          </ul>
        </TimelineNode>
      )}

      {result.finance && (
        <TimelineNode dot="💰">
          <strong>Finanças</strong>
          <ul style={{ margin: '0.25rem 0 0', listStyle: 'none', padding: 0 }}>
            {result.finance.gate > 0 && <li>Bilheteria: +{formatBRL(result.finance.gate)}</li>}
            {result.finance.matchCost > 0 && <li>Custo da partida: −{formatBRL(result.finance.matchCost)}</li>}
            {result.finance.prize > 0 && <li>Premiação: +{formatBRL(result.finance.prize)}</li>}
            <li><strong>Resultado:</strong> {formatBRL(result.finance.net + result.finance.prize)}</li>
            <li><strong>Saldo:</strong> {formatBRL(result.finance.balance)}</li>
          </ul>
        </TimelineNode>
      )}

      {result.seasonFinished && (
        <TimelineNode dot="🏆">
          <strong style={{ color: 'var(--cec-green)' }}>Temporada encerrada!</strong>
          <div style={{ marginTop: '0.5rem' }}>
            <Link to={seasonSummaryUrl}>
              <button className="cec-btn">Ver resumo da temporada</button>
            </Link>
          </div>
        </TimelineNode>
      )}
    </>
  )
}

/**
 * Presentational card rendering a single season-advance outcome (MATCH, TRANSFER or
 * FINISHED) as a timeline. Used both for the live "just advanced" entry and for each
 * persisted item in the News feed.
 */
export function AdvanceEventCard({ result, saveGameId, competitionId, managedClubId }: AdvanceEventCardProps) {
  const seasonSummaryUrl =
    `/season-summary?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`

  const dateStr = formatDatePtBR(result.date)
  const roundLabel = result.type === 'MATCH' && result.round !== null
    ? `${result.competitionName} · Rodada ${result.round + 1}`
    : null

  return (
    <article
      style={{
        background: '#fff',
        borderRadius: '10px',
        padding: '1.25rem',
        boxShadow: '0 2px 10px rgba(0,0,0,0.12)',
      }}
    >
      {/* Header */}
      <div style={{ marginBottom: '1rem', borderBottom: '2px solid var(--cec-yellow)', paddingBottom: '0.75rem' }}>
        {dateStr && (
          <div style={{ fontWeight: 700, fontSize: '1.05rem', color: 'var(--cec-black)' }}>
            {dateStr}
          </div>
        )}
        {roundLabel && (
          <div style={{ fontSize: '0.875rem', color: '#555', marginTop: '2px' }}>
            {roundLabel}
          </div>
        )}
        {result.type === 'TRANSFER' && (
          <div style={{ fontWeight: 700, fontSize: '1.05rem' }}>Janela de Transferências</div>
        )}
        {result.type === 'FINISHED' && (
          <div style={{ fontWeight: 700, fontSize: '1.05rem', color: 'var(--cec-green)' }}>
            Temporada Encerrada
          </div>
        )}
      </div>

      {/* Timeline body */}
      <div>
        {result.type === 'MATCH' && (
          <MatchTimeline
            result={result}
            saveGameId={saveGameId}
            competitionId={competitionId}
            managedClubId={managedClubId}
          />
        )}

        {result.type === 'TRANSFER' && (
          <TimelineNode dot="🔄">
            <span>
              Janela de transferências —{' '}
              <strong>{result.bidsReceived}</strong> proposta(s) recebida(s)
            </span>
            <div style={{ marginTop: '0.5rem' }}>
              <Link to={`/transfers?saveGameId=${saveGameId}&clubId=${managedClubId}`}>
                <button className="cec-btn" style={{ fontSize: '0.875rem' }}>Ver propostas</button>
              </Link>
            </div>
          </TimelineNode>
        )}

        {result.type === 'FINISHED' && (
          <TimelineNode dot="🏆">
            <strong style={{ color: 'var(--cec-green)' }}>Temporada encerrada!</strong>
            <div style={{ marginTop: '0.5rem' }}>
              <Link to={seasonSummaryUrl}>
                <button className="cec-btn">Ver resumo da temporada</button>
              </Link>
            </div>
          </TimelineNode>
        )}
      </div>
    </article>
  )
}
