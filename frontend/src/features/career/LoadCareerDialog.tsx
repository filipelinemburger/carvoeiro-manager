import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Box, Button, Chip, CircularProgress, Dialog, DialogContent, DialogTitle,
  IconButton, Stack, Typography,
} from '@mui/material'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import { useAuth } from '../../auth/AuthContext'
import { listCareers, deleteCareer } from '../../api/career'
import { saveGameKeyFor } from './saveGameKey'
import type { SaveGameSummary } from '../../api/types'

type Props = { open: boolean; onClose: () => void }

/** Lists the user's careers so they can resume or delete one. */
export function LoadCareerDialog({ open, onClose }: Props) {
  const { user, token } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const storageKey = saveGameKeyFor(user?.userId)

  const { data: careers, isLoading, isError } = useQuery<SaveGameSummary[]>({
    queryKey: ['careers'],
    queryFn: () => listCareers(token ?? ''),
    enabled: open && Boolean(token),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteCareer(id, token ?? ''),
    onSuccess: (_data, id) => {
      // If the deleted career was the active one, forget it so the dashboard resets.
      if (localStorage.getItem(storageKey) === id) {
        localStorage.removeItem(storageKey)
        queryClient.invalidateQueries({ queryKey: ['saveGame'] })
      }
      queryClient.invalidateQueries({ queryKey: ['careers'] })
    },
  })

  const resume = (career: SaveGameSummary) => {
    localStorage.setItem(storageKey, career.id)
    queryClient.invalidateQueries({ queryKey: ['saveGame'] })
    onClose()
    void navigate('/dashboard')
  }

  const confirmDelete = (career: SaveGameSummary) => {
    const label = career.managedClubName ?? 'esta carreira'
    if (window.confirm(`Excluir a carreira de ${label}? Esta ação não pode ser desfeita.`)) {
      deleteMutation.mutate(career.id)
    }
  }

  const activeId = localStorage.getItem(storageKey)

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Carregar jogo salvo</DialogTitle>
      <DialogContent dividers>
        {isLoading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}><CircularProgress /></Box>
        )}
        {isError && <Alert severity="error">Erro ao carregar as carreiras.</Alert>}
        {careers && careers.length === 0 && (
          <Typography sx={{ color: 'text.secondary', py: 2 }}>Nenhuma carreira salva.</Typography>
        )}
        {careers && careers.length > 0 && (
          <Stack spacing={1.5} sx={{ py: 1 }}>
            {careers.map(career => (
              <Box
                key={career.id}
                sx={{
                  display: 'flex', alignItems: 'center', gap: 1,
                  p: 1.5, border: '1px solid', borderColor: 'divider', borderRadius: 1,
                }}
              >
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography sx={{ fontWeight: 700 }}>
                      {career.managedClubName ?? 'Sem clube'}
                    </Typography>
                    {career.id === activeId && <Chip size="small" color="primary" label="atual" />}
                  </Stack>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Temporada {career.seasonYear} · Rodada {career.currentRound} · criado em{' '}
                    {new Date(career.createdAt).toLocaleDateString('pt-BR')}
                  </Typography>
                </Box>
                <Button size="small" variant="contained" onClick={() => resume(career)}>
                  Carregar
                </Button>
                <IconButton
                  aria-label={`Excluir carreira de ${career.managedClubName ?? 'sem clube'}`}
                  color="error"
                  disabled={deleteMutation.isPending}
                  onClick={() => confirmDelete(career)}
                >
                  <DeleteOutlineIcon />
                </IconButton>
              </Box>
            ))}
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  )
}
