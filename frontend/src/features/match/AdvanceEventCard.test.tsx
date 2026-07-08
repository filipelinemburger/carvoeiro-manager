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
})
