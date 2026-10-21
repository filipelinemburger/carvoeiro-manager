import { Chip, List, ListItem, Stack, Typography } from '@mui/material'
import type { BoardEvent, BoardNewsType } from '../../api/types'
import { moodLabel, objectiveChip, objectiveText } from './boardLabels'

const BODY: Partial<Record<BoardNewsType, string>> = {
  BOARD_ULTIMATUM: 'A diretoria exige reação imediata.',
  BOARD_ULTIMATUM_LIFTED: 'Os resultados recentes acalmaram a diretoria. O ultimato foi retirado.',
  BOARD_SACKED: 'A diretoria decidiu encerrar o seu trabalho no clube.',
  BOARD_SACKING_AVERTED: 'A diretoria queria sua saída, mas decidiu mantê-lo no cargo.',
}

/** A board news item's content, rendered entirely from its {@code board} payload. */
export function BoardNewsBody({ type, board }: { type: BoardNewsType; board: BoardEvent }) {
  return (
    <Stack spacing={1}>
      {BODY[type] && <Typography>{BODY[type]}</Typography>}
      {board.objectives.length > 0 && (
        <List dense disablePadding>
          {board.objectives.map(o => {
            const chip = type === 'BOARD_OBJECTIVES' ? null : objectiveChip(o)
            return (
              <ListItem key={o.kind} disableGutters sx={{ display: 'flex', gap: 1 }}>
                <Typography variant="body2" sx={{ flex: 1 }}>{objectiveText(o)}</Typography>
                {chip && <Chip size="small" variant="outlined" label={chip.label} color={chip.color} />}
              </ListItem>
            )
          })}
        </List>
      )}
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Confiança da diretoria: {board.confidence}/100 ({moodLabel(board.confidence)})
      </Typography>
    </Stack>
  )
}
