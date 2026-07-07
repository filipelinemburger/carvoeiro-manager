import { describe, it, expect } from 'vitest'
import { MENTALITY_LABEL, PRESSING_LABEL, MARKING_LABEL } from './tacticsLabels'

describe('tacticsLabels', () => {
  it('maps every mentality to its friendly Portuguese label', () => {
    expect(MENTALITY_LABEL).toEqual({
      DEFENSIVE: 'Defensiva',
      BALANCED: 'Equilibrada',
      ATTACKING: 'Ofensiva',
    })
  })

  it('maps every pressing level to its friendly Portuguese label', () => {
    expect(PRESSING_LABEL).toEqual({ LOW: 'Baixa', MEDIUM: 'Média', HIGH: 'Alta' })
  })

  it('maps every marking scheme to its friendly Portuguese label', () => {
    expect(MARKING_LABEL).toEqual({ ZONAL: 'Por zona', MAN_TIGHT: 'Individual' })
  })
})
