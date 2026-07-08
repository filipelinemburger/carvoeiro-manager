import type { ListedReason } from '../../api/types'

/** pt-BR chip text for why a player is transfer-listed. */
export const LISTED_REASON_LABEL: Record<ListedReason, string> = {
  PLAYER_REQUEST: 'pediu para sair',
  UNUSED: 'pouco utilizado',
  FINANCIAL: 'problemas financeiros',
  SURPLUS: 'excesso no elenco',
  OWNER: 'colocado à venda',
}
