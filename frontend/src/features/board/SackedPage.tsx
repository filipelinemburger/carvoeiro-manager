import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Box, Button, Card, CardContent, Chip, List, ListItem, Stack, Typography } from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { getBoard, getBoardHistory } from '../../api/board'
import { getSeasonHistory } from '../../api/career'
import type { SaveGameResponse } from '../../api/types'
import { NewCareerDialog } from '../career/NewCareerDialog'
import { moodLabel, objectiveChip, objectiveText } from './boardLabels'

/**
 * Shown in place of every page (but the final table) once the board has sacked the manager.
 * The save is frozen, so this is a career's last screen: the verdict, the record, a way on.
 */
export function SackedPage({ saveGame }: { saveGame: SaveGameResponse }) {
  const { token } = useAuth()
  const navigate = useNavigate()
  const [newCareerOpen, setNewCareerOpen] = useState(false)

  const { data: board } = useQuery({
    queryKey: ['board', saveGame.id],
    queryFn: () => getBoard(saveGame.id, token ?? ''),
    enabled: Boolean(token),
  })
  const { data: history } = useQuery({
    queryKey: ['boardHistory', saveGame.id],
    queryFn: () => getBoardHistory(saveGame.id, token ?? ''),
    enabled: Boolean(token),
  })
  const { data: seasons } = useQuery({
    queryKey: ['seasonHistory', saveGame.id],
    queryFn: () => getSeasonHistory(saveGame.id, token ?? ''),
    enabled: Boolean(token),
  })

  // Archived seasons only: the season the manager was sacked in is never rolled over.
  const titles = (seasons ?? []).filter(s => s.managedClubPosition === 1).length
  const seasonsManaged = (history ?? []).length

  return (
    <Box sx={{ p: { xs: 1, sm: 2 }, maxWidth: 720 }}>
      <Typography variant="h4" component="h2" sx={{ fontWeight: 800 }}>Você foi demitido</Typography>
      <Typography sx={{ mt: 1, color: 'text.secondary' }}>
        A diretoria do {saveGame.managedClubName} decidiu encerrar o seu trabalho.
        {board && ` Confiança final: ${board.confidence}/100 (${moodLabel(board.confidence)}).`}
      </Typography>

      <Stack direction="row" spacing={1} useFlexGap sx={{ mt: 2, flexWrap: 'wrap' }}>
        <Button variant="contained" onClick={() => setNewCareerOpen(true)}>Nova carreira</Button>
        <Button variant="outlined" onClick={() => void navigate(`/table?saveGameId=${saveGame.id}`)}>
          Ver tabela final
        </Button>
      </Stack>

      <Card variant="outlined" sx={{ mt: 3 }}>
        <CardContent>
          <Typography variant="h6" component="h3" sx={{ fontWeight: 700 }}>Sua carreira</Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
            {seasonsManaged} temporada(s) no comando · {titles} título(s)
          </Typography>
          {(history ?? []).map(season => (
            <Box key={season.seasonYear} sx={{ mt: 1.5 }}>
              <Typography sx={{ fontWeight: 700 }}>Temporada {season.seasonYear}</Typography>
              <List dense disablePadding>
                {season.objectives.map(o => {
                  const chip = objectiveChip(o)
                  return (
                    <ListItem key={o.kind} disableGutters sx={{ display: 'flex', gap: 1 }}>
                      <Typography variant="body2" sx={{ flex: 1 }}>{objectiveText(o)}</Typography>
                      {chip && <Chip size="small" variant="outlined" label={chip.label} color={chip.color} />}
                    </ListItem>
                  )
                })}
              </List>
            </Box>
          ))}
        </CardContent>
      </Card>

      <NewCareerDialog open={newCareerOpen} onClose={() => setNewCareerOpen(false)} />
    </Box>
  )
}
