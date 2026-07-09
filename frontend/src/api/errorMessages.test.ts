import { describe, expect, it } from 'vitest'
import { ApiError } from './client'
import { errorMessage } from './errorMessages'

describe('errorMessage', () => {
  it('maps every known transfer error code to its pt-BR text', () => {
    const cases: Array<[string, string]> = [
      ['TRANSFER_WINDOW_CLOSED', 'Janela de transferências fechada.'],
      ['SQUAD_FULL', 'Elenco cheio — venda antes de contratar.'],
      ['INSUFFICIENT_FUNDS', 'Saldo insuficiente para esta proposta.'],
      ['LIVE_MATCH_IN_PROGRESS', 'Finalize a partida ao vivo antes de negociar.'],
      ['SQUAD_TOO_SMALL', 'O elenco ficaria pequeno demais.'],
      ['INVALID_TRANSFER', 'Proposta inválida.'],
      ['INVALID_CONTRACT', 'Termos de contrato inválidos.'],
      ['TRANSFER_OFFER_NOT_FOUND', 'Proposta não encontrada.'],
    ]
    for (const [code, text] of cases) {
      expect(errorMessage(new ApiError('x', code, 422), 'fallback')).toBe(text)
    }
  })

  it('falls back for unknown codes, plain Errors and non-errors', () => {
    expect(errorMessage(new ApiError('x', 'SOMETHING_ELSE', 500), 'fb')).toBe('fb')
    expect(errorMessage(new ApiError('x', null, 500), 'fb')).toBe('fb')
    expect(errorMessage(new Error('x'), 'fb')).toBe('fb')
    expect(errorMessage(undefined, 'fb')).toBe('fb')
  })
})
