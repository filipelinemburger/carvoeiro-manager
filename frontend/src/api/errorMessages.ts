import { ApiError } from './client'

/** pt-BR texts for backend ErrorCodes surfaced in the transfer and staff flows. */
const MESSAGES: Record<string, string> = {
  STALE_STAFF_CANDIDATES: 'A lista de candidatos mudou — atualizamos as opções, escolha novamente.',
  TRANSFER_WINDOW_CLOSED: 'Janela de transferências fechada.',
  SQUAD_FULL: 'Elenco cheio — venda antes de contratar.',
  INSUFFICIENT_FUNDS: 'Saldo insuficiente para esta proposta.',
  LIVE_MATCH_IN_PROGRESS: 'Finalize a partida ao vivo antes de negociar.',
  SQUAD_TOO_SMALL: 'O elenco ficaria pequeno demais.',
  INVALID_TRANSFER: 'Proposta inválida.',
  INVALID_CONTRACT: 'Termos de contrato inválidos.',
  TRANSFER_OFFER_NOT_FOUND: 'Proposta não encontrada.',
  PLAYER_REJECTS_TERMS: 'O jogador recusou os termos propostos.',
  PLAYER_NOT_PRE_CONTRACT_ELIGIBLE: 'Jogador não elegível para pré-contrato.',
  PRE_CONTRACT_ALREADY_EXISTS: 'Este jogador já tem um pré-contrato ativo.',
  PRE_CONTRACT_NOT_FOUND: 'Pré-contrato não encontrado.',
  PRE_CONTRACT_WINDOW_CLOSED: 'Janela de pré-contratos fechada.',
  RENEWAL_BLOCKED_BY_PRE_CONTRACT: 'Não é possível renovar — o jogador já tem um pré-contrato com outro clube.',
}

/** The specific pt-BR reason for a known ApiError code, else the caller's fallback. */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code !== null && err.code in MESSAGES) {
    return MESSAGES[err.code]
  }
  return fallback
}
