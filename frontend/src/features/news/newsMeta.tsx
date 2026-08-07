import type { ReactNode } from 'react'
import SportsSoccerIcon from '@mui/icons-material/SportsSoccer'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import VisibilityIcon from '@mui/icons-material/Visibility'
import FitnessCenterIcon from '@mui/icons-material/FitnessCenter'
import EmojiEventsIcon from '@mui/icons-material/EmojiEvents'
import EditIcon from '@mui/icons-material/Edit'
import ArticleIcon from '@mui/icons-material/Article'
import QueryStatsIcon from '@mui/icons-material/QueryStats'
import type { AdvanceResponse } from '../../api/types'

/** Short headline for a news list row, derived from the payload type. */
export function newsTitle(payload: AdvanceResponse): string {
  switch (payload.type) {
    case 'MATCH':
      return payload.round !== null ? `Rodada ${payload.round + 1} — resultados` : 'Resultados'
    case 'TRANSFER':
      return `Janela de transferências — ${payload.bidsReceived} proposta(s)`
    case 'WATCHLIST':
      return `Alertas — ${payload.playerAlerts?.length ?? 0} observado(s)`
    case 'PRECONTRACT':
      return 'Pré-contratos'
    case 'TRAINING_REPORT':
      return 'Relatório de treino'
    case 'FINISHED':
      return 'Temporada encerrada'
    case 'MATCH_PREVIEW':
      return `Análise do adversário — ${payload.matchPreview?.opponentName ?? ''}`
    // Phase transitions carry their whole message in competitionName (sparse payload).
    case 'PHASE_QUALIFIED':
      return 'Classificado!'
    case 'PHASE_ELIMINATED':
      return 'Eliminado'
    case 'KNOCKOUT_DRAW':
      return 'Sorteio do mata-mata'
    case 'CHAMPION':
      return 'Campeão!'
    default:
      return 'Notícia'
  }
}

/** Leading icon for a news list row. */
export function newsIcon(type: AdvanceResponse['type']): ReactNode {
  switch (type) {
    case 'MATCH': return <SportsSoccerIcon fontSize="small" />
    case 'TRANSFER': return <SwapHorizIcon fontSize="small" />
    case 'WATCHLIST': return <VisibilityIcon fontSize="small" />
    case 'TRAINING_REPORT': return <FitnessCenterIcon fontSize="small" />
    case 'FINISHED': return <EmojiEventsIcon fontSize="small" />
    case 'PRECONTRACT': return <EditIcon fontSize="small" />
    case 'MATCH_PREVIEW': return <QueryStatsIcon fontSize="small" />
    case 'PHASE_QUALIFIED': return <EmojiEventsIcon fontSize="small" />
    case 'PHASE_ELIMINATED': return <ArticleIcon fontSize="small" />
    case 'KNOCKOUT_DRAW': return <SportsSoccerIcon fontSize="small" />
    case 'CHAMPION': return <EmojiEventsIcon fontSize="small" />
    default: return <ArticleIcon fontSize="small" />
  }
}
