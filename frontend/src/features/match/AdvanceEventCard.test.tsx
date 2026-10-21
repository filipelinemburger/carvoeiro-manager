import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { AdvanceEventCard } from './AdvanceEventCard'
import type { AdvanceResponse } from '../../api/types'

const FINANCE = {
  gate: 1_935_000,
  matchCost: 76_000,
  sponsorship: 0,
  wages: 0,
  upkeep: 0,
  prize: 0,
  net: 1_859_000,
  balance: 12_500_000,
}

const MATCH_RESULT: AdvanceResponse = {
  type: 'MATCH',
  date: '2026-03-15',
  competitionName: 'Campeonato Catarinense',
  round: 4,
  results: [
    { fixtureId: 'f1', homeClubId: 'c1', homeClubName: 'Criciúma', homeGoals: 2, awayClubId: 'c2', awayClubName: 'Figueirense', awayGoals: 1 },
    { fixtureId: 'f2', homeClubId: 'c3', homeClubName: 'Avaí', homeGoals: 0, awayClubId: 'c4', awayClubName: 'Joinville', awayGoals: 0 },
  ],
  bookings: [{ playerId: 'p1', playerName: 'Bruto Um', type: 'RED_CARD' }],
  newSuspensions: [{ playerId: 'p1', playerName: 'Bruto Um', matches: 1 }],
  newInjuries: [],
  finance: FINANCE,
  bidsReceived: 0,
  seasonFinished: false,
}

const TRANSFER_RESULT: AdvanceResponse = {
  type: 'TRANSFER',
  date: '2026-07-01',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 3,
  seasonFinished: false,
}

const FINISHED_RESULT: AdvanceResponse = {
  type: 'FINISHED',
  date: '2026-12-01',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 0,
  seasonFinished: true,
}

const TRAINING_REPORT_RESULT: AdvanceResponse = {
  type: 'TRAINING_REPORT',
  date: '2026-03-16',
  competitionName: null,
  round: null,
  results: [],
  bookings: [],
  newSuspensions: [],
  newInjuries: [],
  finance: null,
  bidsReceived: 0,
  seasonFinished: false,
  trainingReport: {
    staffName: 'Preparador Teste',
    quality: 'EXCELENTE',
    entries: [
      { playerName: 'Fulano', message: 'Fulano evoluiu em Passe (50 → 51).' },
      { playerName: 'Beltrano', message: 'Beltrano está perto de evoluir Finalização.' },
    ],
  },
}

const previewResult: AdvanceResponse = {
  ...MATCH_RESULT,
  type: 'MATCH_PREVIEW',
  date: '2026-03-04',
  matchPreview: {
    fixtureId: 'fx-1', round: 4, matchDate: '2026-03-05', home: true,
    stadiumName: 'Heriberto Hülse',
    opponentClubId: 'club-2', opponentName: 'Avaí', opponentShortName: 'AVA',
    assistantName: 'Cléber Xavier',
    opponentStanding: { position: 3, points: 7, played: 3, won: 2, drawn: 1, lost: 0, goalsFor: 6, goalsAgainst: 2 },
    managedStanding: { position: 5, points: 5, played: 3, won: 1, drawn: 2, lost: 0, goalsFor: 4, goalsAgainst: 3 },
    form: ['V', 'E', 'V'],
    bestPlayers: [{ name: 'Marquinhos Gabriel', position: 'MID', overall: 74 }],
    topScorer: { name: 'Gaspar', goals: 4 },
    tacticalRead: { formation: '4-3-3', mentality: 'ATTACKING', pressing: 'HIGH', marking: 'ZONAL' },
    insights: ['Eles pressionam a saída de bola — bolas longas nas costas da linha de defesa podem render boas chances.'],
  },
}

function renderCard(result: AdvanceResponse) {
  return render(
    <MemoryRouter>
      <AdvanceEventCard
        result={result}
        saveGameId="sg-1"
        competitionId="comp-1"
        managedClubId="club-1"
      />
    </MemoryRouter>,
  )
}

describe('AdvanceEventCard', () => {
  describe('MATCH type', () => {
    it('renders the formatted date', () => {
      renderCard(MATCH_RESULT)
      // Date rendered in pt-BR locale
      expect(screen.getByText(/15\/03\/2026/)).toBeInTheDocument()
    })

    it('renders competition name and round number (1-based)', () => {
      renderCard(MATCH_RESULT)
      expect(screen.getByText(/Campeonato Catarinense.*Rodada 5/)).toBeInTheDocument()
    })

    it('renders fixture nodes with scores', () => {
      renderCard(MATCH_RESULT)
      expect(screen.getByText(/Criciúma/)).toBeInTheDocument()
      expect(screen.getByText(/Figueirense/)).toBeInTheDocument()
      expect(screen.getByText(/Avaí/)).toBeInTheDocument()
    })

    it('shows the managed club match first and in bold', () => {
      const result: AdvanceResponse = {
        ...MATCH_RESULT,
        results: [
          { fixtureId: 'fx-other', homeClubId: 'cX', homeClubName: 'Outro Clube', homeGoals: 1, awayClubId: 'cY', awayClubName: 'Adversário', awayGoals: 2 },
          { fixtureId: 'fx-mine', homeClubId: 'club-1', homeClubName: 'Criciúma EC', homeGoals: 3, awayClubId: 'cZ', awayClubName: 'Rival', awayGoals: 0 },
        ],
      }
      renderCard(result) // renderCard uses managedClubId="club-1"
      const mine = screen.getByText(/Criciúma EC/)
      const other = screen.getByText(/Outro Clube/)
      // The managed club's match is ordered before the other in the DOM, even though
      // it was second in the response.
      expect(mine.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      // ...and its line is rendered bold.
      expect(mine.closest('span')?.style.fontWeight).toBe('700')
    })

    it('renders booking section with card type', () => {
      renderCard(MATCH_RESULT)
      expect(screen.getByText(/Cartões/)).toBeInTheDocument()
      expect(screen.getByText(/Bruto Um — Vermelho/)).toBeInTheDocument()
    })

    it('renders suspension section', () => {
      renderCard(MATCH_RESULT)
      expect(screen.getByText(/Suspensões/)).toBeInTheDocument()
      expect(screen.getByText(/Bruto Um — 1 jogo\(s\)/)).toBeInTheDocument()
    })

    it('does not render injuries section when newInjuries is empty', () => {
      renderCard(MATCH_RESULT)
      expect(screen.queryByText(/Lesões/)).not.toBeInTheDocument()
    })

    it('renders injuries section with player name, severity and match count when injuries present', () => {
      const withInjuries: AdvanceResponse = {
        ...MATCH_RESULT,
        newInjuries: [{ playerId: 'p2', playerName: 'Coxa Fraca', matches: 2, severity: 'MINOR' }],
      }
      renderCard(withInjuries)
      expect(screen.getByText(/Lesões/)).toBeInTheDocument()
      expect(screen.getByText(/Coxa Fraca — Leve \(2 jogo\(s\)\)/)).toBeInTheDocument()
    })

    it('renders finance section with gate, match cost, result and balance', () => {
      renderCard(MATCH_RESULT)
      expect(screen.getByText(/Finanças/)).toBeInTheDocument()
      // Per-match: gate and match cost are shown
      expect(screen.getByText(/Bilheteria/)).toBeInTheDocument()
      expect(screen.getByText(/Custo da partida/)).toBeInTheDocument()
      // Per-match: sponsorship/wages/upkeep are NOT shown (they are monthly now)
      expect(screen.queryByText(/Patrocínio/)).not.toBeInTheDocument()
      expect(screen.queryByText(/Salários/)).not.toBeInTheDocument()
      expect(screen.queryByText(/Manutenção/)).not.toBeInTheDocument()
      expect(screen.getByText(/Saldo:/)).toBeInTheDocument()
    })

    it('renders the Atuações grades section when playerRatings are present', () => {
      const withGrades: AdvanceResponse = {
        ...MATCH_RESULT,
        playerRatings: [
          { playerId: 'p9', name: 'Camisa 9', position: 'ATT', minutesPlayed: 90, goals: 2, rating: 8.6 },
          { playerId: 'p2', name: 'Zagueirão', position: 'DEF', minutesPlayed: 90, goals: 0, rating: 6.4 },
        ],
      }
      renderCard(withGrades)
      expect(screen.getByText(/Atuações/)).toBeInTheDocument()
      expect(screen.getByText('Camisa 9')).toBeInTheDocument()
      expect(screen.getByText('8.6')).toBeInTheDocument()
      expect(screen.getByText('Zagueirão')).toBeInTheDocument()
    })

    it('does not render the Atuações section for older items without playerRatings', () => {
      renderCard(MATCH_RESULT)
      expect(screen.queryByText(/Atuações/)).not.toBeInTheDocument()
    })

    it('does not show season summary link when not finished', () => {
      renderCard(MATCH_RESULT)
      expect(screen.queryByText(/resumo da temporada/i)).not.toBeInTheDocument()
    })

    it('shows season summary CTA when season is finished', () => {
      const finished = { ...MATCH_RESULT, seasonFinished: true }
      renderCard(finished)
      expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
    })

    it('prefers the short club name when present', () => {
      renderCard({
        ...MATCH_RESULT,
        results: [
          {
            fixtureId: 'f1', homeClubId: 'c1',
            homeClubName: 'Criciúma Esporte Clube', homeClubShortName: 'Criciúma', homeGoals: 2,
            awayClubId: 'c2',
            awayClubName: 'Figueirense Futebol Clube', awayClubShortName: 'Figueirense', awayGoals: 1,
          },
        ],
      })
      expect(screen.getByText(/Criciúma\b/)).toBeInTheDocument()
      expect(screen.queryByText(/Criciúma Esporte Clube/)).not.toBeInTheDocument()
      expect(screen.queryByText(/Figueirense Futebol Clube/)).not.toBeInTheDocument()
    })

    it('renders the home stadium as a caption under each result row', () => {
      renderCard({
        ...MATCH_RESULT,
        results: [
          { fixtureId: 'f1', homeClubId: 'c1', homeClubName: 'Criciúma', homeGoals: 2, awayClubId: 'c2', awayClubName: 'Figueirense', awayGoals: 1, stadium: 'Estádio Heriberto Hülse' },
          { fixtureId: 'f2', homeClubId: 'c3', homeClubName: 'Avaí', homeGoals: 0, awayClubId: 'c4', awayClubName: 'Joinville', awayGoals: 0, stadium: 'Estádio da Ressacada' },
        ],
      })
      expect(screen.getByText('Estádio Heriberto Hülse')).toBeInTheDocument()
      expect(screen.getByText('Estádio da Ressacada')).toBeInTheDocument()
    })

    it('does not render a stadium caption for older news payloads without the field (null-safe)', () => {
      renderCard(MATCH_RESULT)
      expect(screen.queryByText(/Estádio/)).not.toBeInTheDocument()
    })

    it('renders attendance and weather chip for a result that has both', () => {
      renderCard({
        ...MATCH_RESULT,
        results: [
          {
            fixtureId: 'f1', homeClubId: 'c1', homeClubName: 'Criciúma', homeGoals: 2,
            awayClubId: 'c2', awayClubName: 'Figueirense', awayGoals: 1,
            stadium: 'Estádio Heriberto Hülse', attendance: 19_300,
            weather: 'CHUVA_FORTE', temperatureC: 16,
          },
        ],
      })
      expect(screen.getByText('Público: 19.300')).toBeInTheDocument()
      expect(screen.getByTestId('weather-chip')).toBeInTheDocument()
    })

    it('renders neither attendance nor weather when both are null', () => {
      renderCard({
        ...MATCH_RESULT,
        results: [
          {
            fixtureId: 'f1', homeClubId: 'c1', homeClubName: 'Criciúma', homeGoals: 2,
            awayClubId: 'c2', awayClubName: 'Figueirense', awayGoals: 1,
            stadium: 'Estádio Heriberto Hülse', attendance: null, weather: null, temperatureC: null,
          },
        ],
      })
      expect(screen.queryByText(/Público/)).not.toBeInTheDocument()
      expect(screen.queryByTestId('weather-chip')).not.toBeInTheDocument()
    })
  })

  describe('TRANSFER type', () => {
    it('renders bid count', () => {
      renderCard(TRANSFER_RESULT)
      expect(screen.getByText(/proposta\(s\) recebida\(s\)/)).toBeInTheDocument()
      // The strong element with the count
      const countEl = screen.getByText('3')
      expect(countEl.tagName.toLowerCase()).toBe('strong')
    })

    it('renders a link to transfers page', () => {
      renderCard(TRANSFER_RESULT)
      expect(screen.getByRole('button', { name: /ver propostas/i })).toBeInTheDocument()
    })

    it('does not render the Transferências section when transfers is absent (old news payloads)', () => {
      renderCard(TRANSFER_RESULT)
      expect(screen.queryByText('Transferências')).not.toBeInTheDocument()
    })

    it('does not render the Transferências section when transfers is null', () => {
      renderCard({ ...TRANSFER_RESULT, transfers: null })
      expect(screen.queryByText('Transferências')).not.toBeInTheDocument()
    })

    it('does not render the Transferências section when transfers is empty', () => {
      renderCard({ ...TRANSFER_RESULT, transfers: [] })
      expect(screen.queryByText('Transferências')).not.toBeInTheDocument()
    })

    it('renders each AI-to-AI trade when transfers are present', () => {
      renderCard({
        ...TRANSFER_RESULT,
        transfers: [
          { playerName: 'Zagueirão', fromClubName: 'Criciúma', toClubName: 'Figueirense', fee: 1_500_000 },
          { playerName: 'Ponta Veloz', fromClubName: 'Avaí', toClubName: 'Joinville', fee: 800_000 },
        ],
      })
      expect(screen.getByText('Transferências')).toBeInTheDocument()
      expect(screen.getByText(/Zagueirão: Criciúma → Figueirense/)).toBeInTheDocument()
      expect(screen.getByText(/Ponta Veloz: Avaí → Joinville/)).toBeInTheDocument()
    })
  })

  describe('WATCHLIST type', () => {
    const watchlistResult = (detail: string | null): AdvanceResponse => ({
      ...TRANSFER_RESULT,
      type: 'WATCHLIST',
      bidsReceived: 0,
      playerAlerts: [{ kind: 'OFFER_RECEIVED', playerName: 'Nicolas', clubName: 'Brusque Futebol Clube', detail }],
    })

    it('formats an offer amount sent as a plain BigDecimal string as pt-BR currency', () => {
      // WatchlistNotifier writes the amount with BigDecimal.toPlainString() — e.g. "1478290.50".
      renderCard(watchlistResult('1478290.50'))
      expect(screen.getByText(/Nicolas recebeu proposta do Brusque Futebol Clube \(R\$\s1\.478\.290,50\)/)).toBeInTheDocument()
    })

    it('omits the amount when the alert carries no detail', () => {
      renderCard(watchlistResult(null))
      expect(screen.getByText(/Nicolas recebeu proposta do Brusque Futebol Clube$/)).toBeInTheDocument()
    })
  })

  describe('FINISHED type', () => {
    it('renders temporada encerrada message', () => {
      renderCard(FINISHED_RESULT)
      expect(screen.getAllByText(/temporada encerrada/i).length).toBeGreaterThan(0)
    })

    it('renders season summary CTA', () => {
      renderCard(FINISHED_RESULT)
      expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
    })
  })

  describe('TRAINING_REPORT type', () => {
    it('renders the report header and the formatted date', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText('Relatório de Treino')).toBeInTheDocument()
      expect(screen.getByText(/16\/03\/2026/)).toBeInTheDocument()
    })

    it('attributes the report to the physio with the quality label', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText(/Preparador Físico Preparador Teste · Excelente/)).toBeInTheDocument()
    })

    it('renders every entry message', () => {
      renderCard(TRAINING_REPORT_RESULT)
      expect(screen.getByText('Fulano evoluiu em Passe (50 → 51).')).toBeInTheDocument()
      expect(screen.getByText('Beltrano está perto de evoluir Finalização.')).toBeInTheDocument()
    })

    it('renders nothing report-specific when trainingReport is null (defensive)', () => {
      renderCard({ ...TRAINING_REPORT_RESULT, trainingReport: null })
      expect(screen.getByText('Relatório de Treino')).toBeInTheDocument()
      expect(screen.queryByText(/Preparador Físico/)).not.toBeInTheDocument()
    })
  })

  describe('MATCH_PREVIEW type', () => {
    it('renders the opponent analysis with byline, standings, form and insights', () => {
      renderCard(previewResult)
      expect(screen.getByText('Análise do Adversário')).toBeInTheDocument()
      expect(screen.getByText(/por Cléber Xavier, Auxiliar Técnico/)).toBeInTheDocument()
      expect(screen.getByText('Avaí')).toBeInTheDocument()
      expect(screen.getByText('Marquinhos Gabriel')).toBeInTheDocument()
      expect(screen.getByText(/Gaspar/)).toBeInTheDocument()
      expect(screen.getByText(/4-3-3/)).toBeInTheDocument()
      expect(screen.getByText(/bolas longas nas costas/)).toBeInTheDocument()
    })

    it('renders a gated (basic) preview without the absent sections', () => {
      const basic = { ...previewResult, matchPreview: { ...previewResult.matchPreview!,
        topScorer: null, tacticalRead: null, insights: null } }
      renderCard(basic)
      expect(screen.queryByText(/4-3-3/)).not.toBeInTheDocument()
    })

    it('shows the weather chip and the auxiliar’s advice when the forecast is revealed', () => {
      const withWeather = { ...previewResult, matchPreview: { ...previewResult.matchPreview!,
        weather: 'CHUVA_FORTE', temperatureC: 16,
        weatherAdvice: 'Chuva forte prevista — considere um time mais físico.' } }
      renderCard(withWeather)
      expect(screen.getByTestId('weather-chip')).toBeInTheDocument()
      expect(screen.getByText(/Chuva forte · 16°C/)).toBeInTheDocument()
      expect(screen.getByText('Chuva forte prevista — considere um time mais físico.')).toBeInTheDocument()
    })

    it('renders neither the chip nor the advice line when the forecast is not yet revealed (null-safe)', () => {
      renderCard(previewResult)
      expect(screen.queryByTestId('weather-chip')).not.toBeInTheDocument()
    })

    it('does not render an advice line when the weather is unremarkable (weatherAdvice null)', () => {
      const withWeather = { ...previewResult, matchPreview: { ...previewResult.matchPreview!,
        weather: 'ENSOLARADO', temperatureC: 24, weatherAdvice: null } }
      renderCard(withWeather)
      expect(screen.getByTestId('weather-chip')).toBeInTheDocument()
      expect(screen.queryByText(/considere/)).not.toBeInTheDocument()
    })
  })

  describe('board', () => {
    const board = {
      confidence: 57, delta: -3, ultimatumIssued: false, ultimatumLifted: false,
      sacked: false, sackingAverted: false, objectives: [],
    }

    it('shows the board change on a match', () => {
      renderCard({ ...MATCH_RESULT, board })
      expect(screen.getByText('Diretoria −3')).toBeInTheDocument()
    })

    it('shows nothing for the board when the match moved nothing', () => {
      renderCard({ ...MATCH_RESULT, board: { ...board, delta: 0 } })
      expect(screen.queryByText(/Diretoria/)).not.toBeInTheDocument()
    })

    it('says so when the match got the manager sacked', () => {
      renderCard({ ...MATCH_RESULT, board: { ...board, sacked: true } })
      expect(screen.getByText('Você foi demitido.')).toBeInTheDocument()
    })

    it('renders an objectives item from its board payload', () => {
      renderCard({
        ...TRANSFER_RESULT, type: 'BOARD_OBJECTIVES', bidsReceived: 0,
        board: {
          ...board, delta: 0, confidence: 60,
          objectives: [{ kind: 'LEAGUE', competitionName: 'Série B', tier: 2, targetPosition: 4, targetStage: null,
            currentPosition: null, progress: null, outcome: 'PENDING' }],
        },
      })
      expect(screen.getByText('Metas da diretoria')).toBeInTheDocument()
      expect(screen.getByText('Terminar no G-4')).toBeInTheDocument()
      expect(screen.getByText(/60\/100 \(Satisfeita\)/)).toBeInTheDocument()
    })

    it('renders the ultimatum and the averted sacking with their messages', () => {
      const { unmount } = renderCard({ ...TRANSFER_RESULT, type: 'BOARD_ULTIMATUM', bidsReceived: 0, board })
      expect(screen.getByText('A diretoria exige reação imediata.')).toBeInTheDocument()
      unmount()
      renderCard({ ...TRANSFER_RESULT, type: 'BOARD_SACKING_AVERTED', bidsReceived: 0, board })
      expect(screen.getByText('A diretoria queria sua saída, mas decidiu mantê-lo no cargo.')).toBeInTheDocument()
    })
  })
})
