import { describe, it, expect } from 'vitest'
import type { BoardChange, BoardObjective } from '../../api/types'
import { changeText, moodColor, moodLabel, objectiveChip, objectiveText, signed } from './boardLabels'

function objective(over: Partial<BoardObjective>): BoardObjective {
  return {
    kind: 'LEAGUE', competitionName: 'Série B', tier: 2, targetPosition: null, targetStage: null,
    currentPosition: null, progress: null, outcome: 'PENDING', ...over,
  }
}

describe('moodLabel', () => {
  it.each([
    [100, 'Muito satisfeita'], [80, 'Muito satisfeita'], [79, 'Satisfeita'], [60, 'Satisfeita'],
    [59, 'Neutra'], [45, 'Neutra'], [44, 'Preocupada'], [30, 'Preocupada'],
    [29, 'Insatisfeita'], [15, 'Insatisfeita'], [14, 'Cargo por um fio'], [0, 'Cargo por um fio'],
  ])('confidence %i reads "%s"', (confidence, label) => {
    expect(moodLabel(confidence)).toBe(label)
  })

  it('colours the bar by the ultimatum lines', () => {
    expect(moodColor(45)).toBe('success')
    expect(moodColor(44)).toBe('warning')
    expect(moodColor(30)).toBe('error')
  })
})

describe('objectiveText', () => {
  it.each([
    [1, 4, 'Terminar no G-4'], [1, 6, 'Terminar no G-6'], [1, 12, 'Terminar entre os 12 primeiros'],
    [1, 16, 'Terminar fora do Z-4'],
    [2, 4, 'Terminar no G-4'], [2, 8, 'Terminar entre os 8 primeiros'],
    [2, 10, 'Terminar na primeira metade da tabela'], [2, 16, 'Terminar fora do Z-4'],
    [3, 14, 'Terminar a primeira fase entre os 14 primeiros'],
  ])('tier %i, position %i reads "%s"', (tier, targetPosition, text) => {
    expect(objectiveText(objective({ tier, targetPosition }))).toBe(text)
  })

  it('a Série C stage target names the stage and the competition', () => {
    expect(objectiveText(objective({ tier: 3, competitionName: 'Série C', targetStage: 'SEMIFINAL' })))
      .toBe('Chegar às semifinais — Série C')
  })

  it.each([
    ['CHAMPION', 'Ser campeão — Campeonato Catarinense'],
    ['FINAL', 'Chegar à final — Campeonato Catarinense'],
    ['SEMIFINAL', 'Chegar às semifinais — Campeonato Catarinense'],
    ['QUARTERFINAL', 'Classificar para as quartas de final — Campeonato Catarinense'],
  ] as const)('state stage %s reads "%s"', (targetStage, text) => {
    expect(objectiveText(objective({ kind: 'STATE', tier: null, competitionName: 'Campeonato Catarinense', targetStage })))
      .toBe(text)
  })

  it('finance', () => {
    expect(objectiveText(objective({ kind: 'FINANCE', competitionName: null, tier: null })))
      .toBe('Terminar a temporada com saldo positivo')
  })
})

describe('objectiveChip', () => {
  it('prefers the outcome over live progress', () => {
    expect(objectiveChip(objective({ outcome: 'MET', progress: 'OFF_TRACK' }))).toEqual({ label: 'cumprido', color: 'success' })
    expect(objectiveChip(objective({ outcome: 'FAILED' }))).toEqual({ label: 'não cumprido', color: 'error' })
    expect(objectiveChip(objective({ progress: 'AT_RISK' }))).toEqual({ label: 'em risco', color: 'warning' })
    expect(objectiveChip(objective({}))).toBeNull()
  })
})

describe('changeText', () => {
  const change = (over: Partial<BoardChange>): BoardChange => ({ date: '2026-06-14', reason: 'MATCH_RESULT', delta: -3, payload: null, ...over })

  it('names the result from the score', () => {
    expect(changeText(change({ payload: { opponent: 'Avaí', score: '0–2', home: true } }))).toBe('Derrota para Avaí (0–2)')
    expect(changeText(change({ payload: { opponent: 'Avaí', score: '1–1', home: true } }))).toBe('Empate com Avaí (1–1)')
    expect(changeText(change({ payload: { opponent: 'Avaí', score: '2–0', home: true } }))).toBe('Vitória sobre Avaí (2–0)')
  })

  it('labels the other reasons', () => {
    expect(changeText(change({ reason: 'TABLE_POSITION', payload: { position: 3 } }))).toBe('Posição na tabela (3º)')
    expect(changeText(change({ reason: 'NEW_SEASON' }))).toBe('Nova temporada')
    expect(changeText(change({ reason: 'TITLE_WON' }))).toBe('Título conquistado')
  })

  it('signs with a real minus', () => {
    expect(signed(-3)).toBe('−3')
    expect(signed(4)).toBe('+4')
  })
})
