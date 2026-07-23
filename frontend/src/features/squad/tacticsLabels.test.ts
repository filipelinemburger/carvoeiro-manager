import { describe, it, expect } from 'vitest'
import { MENTALITY_LABEL, GAME_STYLE_LABEL, PRESSING_LABEL, MARKING_LABEL } from './tacticsLabels'

describe('tacticsLabels', () => {
  it('maps every mentality to its friendly Portuguese label', () => {
    expect(MENTALITY_LABEL).toEqual({
      DEFENSIVE: 'Defensiva',
      BALANCED: 'Equilibrada',
      ATTACKING: 'Ofensiva',
    })
  })

  it('maps every game style to its friendly Portuguese label', () => {
    expect(GAME_STYLE_LABEL).toEqual({
      EQUILIBRADO: 'Equilibrado',
      JOGO_DE_PASSES: 'Jogo de passes',
      CONTRA_ATAQUE: 'Contra-ataque',
      JOGO_DIRETO: 'Jogo direto',
      PRESSAO_ALTA: 'Pressão alta',
      RETRANCA: 'Retranca',
    })
  })

  it('maps every pressing level to its friendly Portuguese label', () => {
    expect(PRESSING_LABEL).toEqual({ LOW: 'Baixa', MEDIUM: 'Média', HIGH: 'Alta' })
  })

  it('maps every marking scheme to its friendly Portuguese label', () => {
    expect(MARKING_LABEL).toEqual({ ZONAL: 'Por zona', MAN_TIGHT: 'Individual' })
  })
})
