import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { AdvanceTimeline } from './AdvanceTimeline'
import type { AdvanceResponse } from '../../api/types'

const FINANCE = {
  gate: 1_935_000,
  sponsorship: 560_000,
  wages: 1_352_000,
  upkeep: 172_000,
  prize: 0,
  net: 971_000,
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
  finance: null,
  bidsReceived: 0,
  seasonFinished: true,
}

function renderTimeline(result: AdvanceResponse, onClose = vi.fn()) {
  return render(
    <MemoryRouter>
      <AdvanceTimeline
        result={result}
        saveGameId="sg-1"
        competitionId="comp-1"
        managedClubId="club-1"
        onClose={onClose}
      />
    </MemoryRouter>,
  )
}

describe('AdvanceTimeline', () => {
  describe('MATCH type', () => {
    it('renders the formatted date', () => {
      renderTimeline(MATCH_RESULT)
      // Date rendered in pt-BR locale
      expect(screen.getByText(/15\/03\/2026/)).toBeInTheDocument()
    })

    it('renders competition name and round number (1-based)', () => {
      renderTimeline(MATCH_RESULT)
      expect(screen.getByText(/Campeonato Catarinense.*Rodada 5/)).toBeInTheDocument()
    })

    it('renders fixture nodes with scores', () => {
      renderTimeline(MATCH_RESULT)
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
      renderTimeline(result) // renderTimeline uses managedClubId="club-1"
      const mine = screen.getByText(/Criciúma EC/)
      const other = screen.getByText(/Outro Clube/)
      // The managed club's match is ordered before the other in the DOM, even though
      // it was second in the response.
      expect(mine.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      // ...and its line is rendered bold.
      expect(mine.closest('span')?.style.fontWeight).toBe('700')
    })

    it('renders booking section with card type', () => {
      renderTimeline(MATCH_RESULT)
      expect(screen.getByText(/Cartões/)).toBeInTheDocument()
      expect(screen.getByText(/Bruto Um — Vermelho/)).toBeInTheDocument()
    })

    it('renders suspension section', () => {
      renderTimeline(MATCH_RESULT)
      expect(screen.getByText(/Suspensões/)).toBeInTheDocument()
      expect(screen.getByText(/Bruto Um — 1 jogo\(s\)/)).toBeInTheDocument()
    })

    it('renders finance section with balance', () => {
      renderTimeline(MATCH_RESULT)
      expect(screen.getByText(/Finanças/)).toBeInTheDocument()
      expect(screen.getByText(/Saldo:/)).toBeInTheDocument()
    })

    it('does not show season summary link when not finished', () => {
      renderTimeline(MATCH_RESULT)
      expect(screen.queryByText(/resumo da temporada/i)).not.toBeInTheDocument()
    })

    it('shows season summary CTA when season is finished', () => {
      const finished = { ...MATCH_RESULT, seasonFinished: true }
      renderTimeline(finished)
      expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
    })
  })

  describe('TRANSFER type', () => {
    it('renders bid count', () => {
      renderTimeline(TRANSFER_RESULT)
      expect(screen.getByText(/proposta\(s\) recebida\(s\)/)).toBeInTheDocument()
      // The strong element with the count
      const countEl = screen.getByText('3')
      expect(countEl.tagName.toLowerCase()).toBe('strong')
    })

    it('renders a link to transfers page', () => {
      renderTimeline(TRANSFER_RESULT)
      expect(screen.getByRole('button', { name: /ver propostas/i })).toBeInTheDocument()
    })
  })

  describe('FINISHED type', () => {
    it('renders temporada encerrada message', () => {
      renderTimeline(FINISHED_RESULT)
      expect(screen.getAllByText(/temporada encerrada/i).length).toBeGreaterThan(0)
    })

    it('renders season summary CTA', () => {
      renderTimeline(FINISHED_RESULT)
      expect(screen.getByRole('button', { name: /resumo da temporada/i })).toBeInTheDocument()
    })
  })

  describe('dismiss', () => {
    it('calls onClose when Fechar button is clicked', () => {
      const onClose = vi.fn()
      renderTimeline(MATCH_RESULT, onClose)
      // The bottom "Fechar" button (text content)
      fireEvent.click(screen.getByRole('button', { name: /^fechar$/i }))
      expect(onClose).toHaveBeenCalledTimes(1)
    })

    it('calls onClose when the X (close) button is clicked', () => {
      const onClose = vi.fn()
      renderTimeline(MATCH_RESULT, onClose)
      fireEvent.click(screen.getByRole('button', { name: /fechar modal/i }))
      expect(onClose).toHaveBeenCalled()
    })
  })
})
