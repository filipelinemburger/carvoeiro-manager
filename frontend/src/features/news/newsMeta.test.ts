import { describe, it, expect } from 'vitest'
import { newsTitle } from './newsMeta'
import type { AdvanceResponse } from '../../api/types'

function payload(overrides: Partial<AdvanceResponse>): AdvanceResponse {
  return {
    type: 'MATCH', date: null, competitionName: null, round: null,
    results: [], bookings: [], newSuspensions: [], newInjuries: [],
    finance: null, bidsReceived: 0, seasonFinished: false, ...overrides,
  }
}

describe('newsTitle', () => {
  it('MATCH shows the 1-based round', () => {
    expect(newsTitle(payload({ type: 'MATCH', round: 11 }))).toBe('Rodada 12 — resultados')
  })
  it('MATCH without a round falls back', () => {
    expect(newsTitle(payload({ type: 'MATCH', round: null }))).toBe('Resultados')
  })
  it('TRANSFER shows the bid count', () => {
    expect(newsTitle(payload({ type: 'TRANSFER', bidsReceived: 3 }))).toBe('Janela de transferências — 3 proposta(s)')
  })
  it('WATCHLIST shows the alert count, 0 when absent', () => {
    expect(newsTitle(payload({ type: 'WATCHLIST' }))).toBe('Alertas — 0 observado(s)')
    expect(newsTitle(payload({ type: 'WATCHLIST', playerAlerts: [
      { kind: 'OFFER_RECEIVED', playerName: 'X', clubName: 'Avaí', detail: null },
    ] }))).toBe('Alertas — 1 observado(s)')
  })
  it('fixed titles for TRAINING_REPORT, FINISHED, PRECONTRACT', () => {
    expect(newsTitle(payload({ type: 'TRAINING_REPORT' }))).toBe('Relatório de treino')
    expect(newsTitle(payload({ type: 'FINISHED' }))).toBe('Temporada encerrada')
    expect(newsTitle(payload({ type: 'PRECONTRACT' }))).toBe('Pré-contratos')
  })
})
