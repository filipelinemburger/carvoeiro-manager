import { ApiError } from './client'

/** pt-BR texts for backend ErrorCodes surfaced in the transfer flows. */
const MESSAGES: Record<string, string> = {
  TRANSFER_WINDOW_CLOSED: 'Janela de transferências fechada.',
  SQUAD_FULL: 'Elenco cheio — venda antes de contratar.',
  INSUFFICIENT_FUNDS: 'Saldo insuficiente para esta proposta.',
  LIVE_MATCH_IN_PROGRESS: 'Finalize a partida ao vivo antes de negociar.',
  SQUAD_TOO_SMALL: 'O elenco ficaria pequeno demais.',
  INVALID_TRANSFER: 'Proposta inválida.',
  INVALID_CONTRACT: 'Termos de contrato inválidos.',
  TRANSFER_OFFER_NOT_FOUND: 'Proposta não encontrada.',
}

/** The specific pt-BR reason for a known ApiError code, else the caller's fallback. */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code !== null && err.code in MESSAGES) {
    return MESSAGES[err.code]
  }
  return fallback
}
