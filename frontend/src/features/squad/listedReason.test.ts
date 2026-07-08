import { describe, it, expect } from 'vitest'
import { LISTED_REASON_LABEL } from './listedReason'

describe('listedReason', () => {
  it('maps every listing reason to its pt-BR chip label', () => {
    expect(LISTED_REASON_LABEL).toEqual({
      PLAYER_REQUEST: 'pediu para sair',
      UNUSED: 'pouco utilizado',
      FINANCIAL: 'problemas financeiros',
      SURPLUS: 'excesso no elenco',
      OWNER: 'colocado à venda',
    })
  })
})
