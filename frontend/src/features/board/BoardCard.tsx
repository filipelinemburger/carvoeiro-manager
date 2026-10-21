import { useQuery } from '@tanstack/react-query'
import {
  Alert, Box, Card, CardContent, Chip, LinearProgress, List, ListItem, Stack, Typography,
} from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getBoard } from '../../api/board'
import type { BoardResponse } from '../../api/types'
import {
  changeText, moodColor, moodLabel, objectiveChip, objectiveDetail, objectiveText, signed,
} from './boardLabels'

/** How many of the latest changes the card lists. */
const CHANGES_SHOWN = 5

/** The dashboard's *Diretoria* card: confidence, the season's objectives, and why it moved. */
export function BoardCard({ saveGameId }: { saveGameId: string }) {
  const { token } = useAuth()
  const { data: board } = useQuery<BoardResponse>({
    queryKey: ['board', saveGameId],
    queryFn: () => getBoard(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
  })

  if (!board) return null

  return (
    <Card variant="outlined" sx={{ maxWidth: 480, flex: 1, minWidth: 0 }}>
      <CardContent>
        <Typography variant="h6" component="h3" sx={{ fontWeight: 700, mb: 1 }}>
          Diretoria
        </Typography>

        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
          <Typography sx={{ fontWeight: 700 }}>{moodLabel(board.confidence)}</Typography>
          <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>{board.confidence}/100</Typography>
        </Stack>
        <LinearProgress
          variant="determinate"
          value={board.confidence}
          color={moodColor(board.confidence)}
          aria-label="Confiança da diretoria"
          sx={{ height: 8, borderRadius: 4, mt: 0.5 }}
        />

        {board.ultimatumActive && (
          <Alert severity="error" sx={{ mt: 1.5 }}>
            Ultimato da diretoria: a diretoria exige reação imediata.
          </Alert>
        )}
        {board.graceSeason && (
          <Typography variant="body2" sx={{ mt: 1.5, color: 'text.secondary' }}>
            Temporada de adaptação: a diretoria avalia, mas não demite.
          </Typography>
        )}
        {!board.sackingEnabled && (
          <Typography variant="body2" sx={{ mt: 1.5, color: 'text.secondary' }}>
            Demissão desativada nesta carreira.
          </Typography>
        )}

        <Typography variant="subtitle2" sx={{ fontWeight: 700, mt: 2 }}>Metas da temporada</Typography>
        <List dense disablePadding>
          {board.objectives.map(o => {
            const chip = objectiveChip(o)
            const detail = objectiveDetail(o)
            return (
              <ListItem key={o.kind} disableGutters sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2">{objectiveText(o)}</Typography>
                  {detail && (
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>{detail}</Typography>
                  )}
                </Box>
                {chip && <Chip size="small" label={chip.label} color={chip.color} variant="outlined" />}
              </ListItem>
            )
          })}
        </List>

        {board.recentChanges.length > 0 && (
          <>
            <Typography variant="subtitle2" sx={{ fontWeight: 700, mt: 2 }}>Últimas mudanças</Typography>
            <List dense disablePadding>
              {board.recentChanges.slice(0, CHANGES_SHOWN).map((change, i) => (
                <ListItem key={`${change.date}-${i}`} disableGutters>
                  <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {signed(change.delta)} · {changeText(change)}
                  </Typography>
                </ListItem>
              ))}
            </List>
          </>
        )}
      </CardContent>
    </Card>
  )
}
