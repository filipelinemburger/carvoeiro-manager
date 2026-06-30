import { Link } from 'react-router-dom'
import { formatBRL } from '../../lib/money'
import type { AdvanceResponse } from '../../api/types'

interface AdvanceTimelineProps {
  result: AdvanceResponse
  saveGameId: string
  competitionId: string
  managedClubId: string
  onClose: () => void
}

function formatDatePtBR(isoDate: string | null): string {
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

      {result.finance && (
        <TimelineNode dot="💰">
          <strong>Finanças</strong>
          <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem', listStyle: 'none', padding: 0 }}>
            <li>Bilheteria: {formatBRL(result.finance.gate)}</li>
            <li>Patrocínio: {formatBRL(result.finance.sponsorship)}</li>
            <li>Salários: −{formatBRL(result.finance.wages)}</li>
            <li>Manutenção: −{formatBRL(result.finance.upkeep)}</li>
            {result.finance.prize > 0 && <li>Premiação: {formatBRL(result.finance.prize)}</li>}
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

export function AdvanceTimeline({ result, saveGameId, competitionId, managedClubId, onClose }: AdvanceTimelineProps) {
  const seasonSummaryUrl =
    `/season-summary?saveGameId=${saveGameId}&competitionId=${competitionId}&managedClubId=${managedClubId}`

  const dateStr = formatDatePtBR(result.date)
  const roundLabel = result.type === 'MATCH' && result.round !== null
    ? `${result.competitionName} · Rodada ${result.round + 1}`
    : null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Resultado do avanço"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.65)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
      onClick={e => { if (e.target === e.currentTarget) onClose() }}
    >
      <div
        style={{
          background: '#fff',
          borderRadius: '10px',
          padding: '1.5rem',
          maxWidth: '540px',
          width: 'calc(100% - 2rem)',
          maxHeight: '85vh',
          overflowY: 'auto',
          boxShadow: '0 8px 32px rgba(0,0,0,0.35)',
        }}
      >
        {/* Header */}
        <div style={{ marginBottom: '1rem', borderBottom: '2px solid var(--cec-yellow)', paddingBottom: '0.75rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div>
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
            <button className="cec-icon-btn" onClick={onClose} aria-label="Fechar modal" style={{ color: '#555', fontSize: '1.4rem', lineHeight: 1 }}>
              ✕
            </button>
          </div>
        </div>

        {/* Timeline body */}
        <div style={{ marginBottom: '1rem' }}>
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

        {/* Footer close button */}
        <div style={{ textAlign: 'right', borderTop: '1px solid #eee', paddingTop: '0.75rem' }}>
          <button className="cec-btn" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  )
}
