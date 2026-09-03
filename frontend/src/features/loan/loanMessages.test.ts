import { describe, it, expect } from 'vitest'
import { refusalMessage, isNegotiable } from './loanMessages'

describe('refusalMessage', () => {
  it('explains a KEY_PLAYER refusal as the club refusing to let go of a key man', () => {
    expect(refusalMessage('KEY_PLAYER')).toBe('O clube não abre mão dele: é peça-chave do elenco.')
  })

  it('explains a BELOW_LEVEL refusal as the player being beneath the borrower squad', () => {
    expect(refusalMessage('BELOW_LEVEL')).toBe('Ele não está no nível do elenco que pediu o empréstimo.')
  })

  it('explains a TERMS_REJECTED refusal as an invitation to improve the proposal', () => {
    expect(refusalMessage('TERMS_REJECTED')).toBe('Não gostaram dos termos — tente melhorar a proposta.')
  })
})

describe('isNegotiable', () => {
  it('is true only for TERMS_REJECTED — the one refusal a better offer can still overturn', () => {
    expect(isNegotiable('TERMS_REJECTED')).toBe(true)
  })

  it('is false for KEY_PLAYER — no offer moves a club off its key man', () => {
    expect(isNegotiable('KEY_PLAYER')).toBe(false)
  })

  it('is false for BELOW_LEVEL — no offer changes what level the player is', () => {
    expect(isNegotiable('BELOW_LEVEL')).toBe(false)
  })
})
