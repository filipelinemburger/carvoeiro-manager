import type { LoanRefusal } from '../../api/types'

/** Mirrors LoanService.MIN_CONTRACT_YEARS_TO_LOAN: a loan must end before the contract does, so a
 * player needs at least this many years left to be loaned in either direction. Shared by the
 * lend-out gate (SquadPage) and the borrow gate (TransferMarketPage) so the two can't drift. */
export const MIN_CONTRACT_YEARS_TO_LOAN = 2

/**
 * pt-BR text for a rejected loan offer. The three refusals carry the whole realism rule to the
 * manager, and the distinction between them is load-bearing: KEY_PLAYER and BELOW_LEVEL end the
 * conversation — no better offer moves either — while TERMS_REJECTED is about the terms alone
 * and invites a better one. Any UI that blurs them sends the manager raising the wage share
 * against a wall.
 */
export function refusalMessage(refusal: LoanRefusal): string {
  switch (refusal) {
    case 'KEY_PLAYER':
      return 'O clube não abre mão dele: é peça-chave do elenco.'
    case 'BELOW_LEVEL':
      return 'Ele não está no nível do elenco que pediu o empréstimo.'
    case 'TERMS_REJECTED':
      return 'Não gostaram dos termos — tente melhorar a proposta.'
  }
}

/** Whether a better proposal could still succeed. Only the terms are negotiable. */
export function isNegotiable(refusal: LoanRefusal): boolean {
  return refusal === 'TERMS_REJECTED'
}
