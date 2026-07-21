import { Link } from 'react-router-dom'
import {
  Box, Button, Card, CardContent, Chip, Divider, List, ListItem, Stack, Typography,
} from '@mui/material'
import { formatBRL } from '../../lib/money'
import { formatDatePtBR } from '../../utils/date'
import type { AdvanceResponse } from '../../api/types'
import { SEVERITY_LABEL } from '../squad/injurySeverity'
import { PlayerRatingsList } from './PlayerRatingsList'

const QUALITY_LABEL: Record<string, string> = {
  REGULAR: 'Regular',
  BOM: 'Bom',
  EXCELENTE: 'Excelente',
}

interface AdvanceEventCardProps {
  result: AdvanceResponse
  saveGameId: string
  competitionId: string
  managedClubId: string
}

/** A single timeline entry: a coloured dot/emoji marker plus its content. */
function TimelineNode({ children, dot = '●' }: { children: React.ReactNode; dot?: string }) {
  return (
    <Stack direction="row" spacing={1.5} sx={{ mb: 1 }}>
      <Stack sx={{ alignItems: 'center', minWidth: 20 }}>
        <Box component="span" sx={{ color: 'var(--cec-yellow)', fontSize: '0.9rem', lineHeight: 1 }}>
          {dot}
        </Box>
        <Box sx={{ flex: 1, width: '2px', bgcolor: '#ddd', mt: '4px' }} />
      </Stack>
      <Box sx={{ flex: 1, pb: 1, minWidth: 0 }}>{children}</Box>
    </Stack>
  )
}

/**
 * A season-summary call-to-action. Kept as a real <button> inside a router <Link>
 * so it exposes role="button" (the surrounding <a> is the navigation target).
 */
function SeasonSummaryCTA({ url }: { url: string }) {
  return (
    <Box sx={{ mt: 1 }}>
      <Link to={url} style={{ textDecoration: 'none' }}>
        <Button variant="contained">Ver resumo da temporada</Button>
      </Link>
    </Box>
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
              {/* Kept as a native <span> with inline fontWeight so the "bold managed match"
                  contract stays observable via element.style. */}
              <Typography
                component="span"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.95rem',
                  ...(mine
                    ? {
                        bgcolor: 'rgba(247,212,23,0.3)',
                        px: 0.75,
                        py: '2px',
                        borderRadius: 1,
                        display: 'inline-block',
                      }
                    : {}),
                }}
                style={mine ? { fontWeight: 700 } : undefined}
              >
                {r.homeClubShortName ?? r.homeClubName} <strong>{r.homeGoals}</strong>
                {' — '}
                <strong>{r.awayGoals}</strong> {r.awayClubShortName ?? r.awayClubName}
              </Typography>
            </TimelineNode>
          )
        })}

      {result.playerRatings && result.playerRatings.length > 0 && (
        <TimelineNode dot="📊">
          <Typography component="strong" sx={{ fontWeight: 700 }}>Atuações</Typography>
          <PlayerRatingsList ratings={result.playerRatings} />
        </TimelineNode>
      )}

      {result.bookings.length > 0 && (
        <TimelineNode dot="🟨">
          <Typography component="strong" sx={{ fontWeight: 700 }}>Cartões</Typography>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 0.5 }}>
            {result.bookings.map((b, i) => (
              <Chip
                key={`${b.playerId}-${i}`}
                size="small"
                color={b.type === 'RED_CARD' ? 'error' : 'warning'}
                label={`${b.playerName} — ${b.type === 'RED_CARD' ? 'Vermelho' : 'Amarelo'}`}
              />
            ))}
          </Stack>
        </TimelineNode>
      )}

      {result.newSuspensions.length > 0 && (
        <TimelineNode dot="🚫">
          <Typography component="strong" sx={{ fontWeight: 700 }}>Suspensões</Typography>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 0.5 }}>
            {result.newSuspensions.map((s, i) => (
              <Chip
                key={`${s.playerId}-${i}`}
                size="small"
                variant="outlined"
                label={`${s.playerName} — ${s.matches} jogo(s)`}
              />
            ))}
          </Stack>
        </TimelineNode>
      )}

      {result.newInjuries.length > 0 && (
        <TimelineNode dot="🩹">
          <Typography component="strong" sx={{ fontWeight: 700 }}>Lesões</Typography>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 0.5 }}>
            {result.newInjuries.map((inj, i) => (
              <Chip
                key={`${inj.playerId}-${i}`}
                size="small"
                variant="outlined"
                color="secondary"
                label={`${inj.playerName} — ${SEVERITY_LABEL[inj.severity] ?? inj.severity} (${inj.matches} jogo(s))`}
              />
            ))}
          </Stack>
        </TimelineNode>
      )}

      {result.finance && (
        <TimelineNode dot="💰">
          <Typography component="strong" sx={{ fontWeight: 700 }}>Finanças</Typography>
          <List dense disablePadding sx={{ mt: 0.25 }}>
            {result.finance.gate > 0 && (
              <ListItem disablePadding>Bilheteria: +{formatBRL(result.finance.gate)}</ListItem>
            )}
            {result.finance.matchCost > 0 && (
              <ListItem disablePadding>Custo da partida: −{formatBRL(result.finance.matchCost)}</ListItem>
            )}
            {result.finance.prize > 0 && (
              <ListItem disablePadding>Premiação: +{formatBRL(result.finance.prize)}</ListItem>
            )}
            <ListItem disablePadding>
              <strong>Resultado:</strong>&nbsp;{formatBRL(result.finance.net + result.finance.prize)}
            </ListItem>
            <ListItem disablePadding>
              <strong>Saldo:</strong>&nbsp;{formatBRL(result.finance.balance)}
            </ListItem>
          </List>
        </TimelineNode>
      )}

      {result.seasonFinished && (
        <TimelineNode dot="🏆">
          <Typography component="strong" sx={{ fontWeight: 700, color: 'var(--cec-green)' }}>
            Temporada encerrada!
          </Typography>
          <SeasonSummaryCTA url={seasonSummaryUrl} />
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
    <Card
      component="article"
      elevation={0}
      sx={{ borderRadius: 2.5, boxShadow: '0 2px 10px rgba(0,0,0,0.12)' }}
    >
      <CardContent sx={{ p: { xs: 2, sm: 2.5 } }}>
        {/* Header */}
        <Box sx={{ mb: 2, pb: 1 }}>
          {dateStr && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem', color: 'text.primary' }}>
              {dateStr}
            </Typography>
          )}
          {roundLabel && (
            <Typography sx={{ fontSize: '0.875rem', color: 'text.secondary', mt: '2px' }}>
              {roundLabel}
            </Typography>
          )}
          {result.type === 'TRANSFER' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Janela de Transferências
            </Typography>
          )}
          {result.type === 'PRECONTRACT' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Pré-contrato
            </Typography>
          )}
          {result.type === 'WATCHLIST' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Alertas
            </Typography>
          )}
          {result.type === 'FINISHED' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem', color: 'var(--cec-green)' }}>
              Temporada Encerrada
            </Typography>
          )}
          {result.type === 'TRAINING_REPORT' && (
            <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>
              Relatório de Treino
            </Typography>
          )}
        </Box>
        <Divider sx={{ mb: 2, borderBottomWidth: 2, borderColor: 'var(--cec-yellow)' }} />

        {/* Timeline body */}
        <Box>
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
              <Typography component="span">
                Janela de transferências —{' '}
                <strong>{result.bidsReceived}</strong> proposta(s) recebida(s)
              </Typography>
              <Box sx={{ mt: 1 }}>
                <Link
                  to={`/transfers?saveGameId=${saveGameId}&clubId=${managedClubId}`}
                  style={{ textDecoration: 'none' }}
                >
                  <Button variant="contained" size="small">Ver propostas</Button>
                </Link>
              </Box>
            </TimelineNode>
          )}

          {/* Old news payloads persisted before this field existed deserialize transfers as
              null (not []) — always null-guard before reading it. */}
          {result.type === 'TRANSFER' && result.transfers && result.transfers.length > 0 && (
            <TimelineNode dot="💱">
              <Typography component="strong" sx={{ fontWeight: 700 }}>Transferências</Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.transfers.map((t, i) => (
                  <ListItem key={`${t.playerName}-${i}`} disablePadding>
                    {t.playerName}: {t.fromClubName} → {t.toClubName} ({formatBRL(t.fee)})
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}

          {(result.preContractEvents?.length ?? 0) > 0 && (
            <TimelineNode dot="🖋️">
              <Typography component="strong" sx={{ fontWeight: 700 }}>Pré-contratos</Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.preContractEvents!.map((e, i) => (
                  <ListItem key={`${e.playerName}-${i}`} disablePadding>
                    {e.kind === 'AGREED_OUT' && (
                      <>✅ {e.playerName} aceitou seu pré-contrato (vem de {e.clubName})</>
                    )}
                    {e.kind === 'POACHED_IN' && (
                      <>⚠️ {e.playerName} assinou pré-contrato com {e.clubName} — renove para defender!</>
                    )}
                    {e.kind === 'LOCKED_OUT' && (
                      <>🔒 Pré-contrato de {e.playerName} confirmado — chega na próxima temporada</>
                    )}
                    {e.kind === 'CANCELLED_OUT' && (
                      <>❌ {e.clubName} renovou com {e.playerName} — seu pré-contrato caiu</>
                    )}
                    {e.kind === 'LOCKED_IN' && (
                      <>🔒 {e.playerName} vai mesmo para {e.clubName} no fim do contrato</>
                    )}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}

          {(result.playerAlerts?.length ?? 0) > 0 && (
            <TimelineNode dot="🔭">
              <Typography component="strong" sx={{ fontWeight: 700 }}>Alertas</Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.playerAlerts!.map((a, i) => (
                  <ListItem key={`${a.playerName}-${i}`} disablePadding>
                    {a.kind === 'SOLD' && (<>🔁 {a.playerName} foi vendido ao {a.clubName}</>)}
                    {a.kind === 'LISTED' && (<>📋 {a.playerName} foi listado para transferência ({a.clubName})</>)}
                    {a.kind === 'FREE_AGENT' && (<>🆓 {a.playerName} ficou livre no mercado</>)}
                    {a.kind === 'OFFER_RECEIVED' && (
                      <>📥 {a.playerName} recebeu proposta do {a.clubName}{a.detail ? ` (R$ ${a.detail})` : ''}</>
                    )}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}

          {result.type === 'FINISHED' && (
            <TimelineNode dot="🏆">
              <Typography component="strong" sx={{ fontWeight: 700, color: 'var(--cec-green)' }}>
                Temporada encerrada!
              </Typography>
              <SeasonSummaryCTA url={seasonSummaryUrl} />
            </TimelineNode>
          )}

          {result.type === 'TRAINING_REPORT' && result.trainingReport && (
            <TimelineNode dot="🏋️">
              <Typography component="strong" sx={{ fontWeight: 700 }}>
                Preparador Físico {result.trainingReport.staffName}
                {' · '}
                {QUALITY_LABEL[result.trainingReport.quality] ?? result.trainingReport.quality}
              </Typography>
              <List dense disablePadding sx={{ mt: 0.5 }}>
                {result.trainingReport.entries.map((e, i) => (
                  <ListItem key={`${e.playerName}-${i}`} disablePadding>
                    {e.message}
                  </ListItem>
                ))}
              </List>
            </TimelineNode>
          )}
        </Box>
      </CardContent>
    </Card>
  )
}
