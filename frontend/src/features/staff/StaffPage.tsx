import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogContent, DialogTitle,
  IconButton, List, ListItemButton, ListItemText, Stack, Typography,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import { getStaffRoster, getStaffCandidates, hireStaff } from '../../api/career'
import { useAuth } from '../../auth/AuthContext'
import { formatBRL } from '../../lib/money'
import type { StaffRole, StaffMember, StaffQuality } from '../../api/types'

const ROLE_LABEL: Record<StaffRole, string> = {
  ASSISTENTE: 'Auxiliar Técnico',
  PREPARADOR_FISICO: 'Preparador Físico',
  OLHEIRO: 'Olheiro',
  FISIOTERAPEUTA: 'Fisioterapeuta',
}

const QUALITY_LABEL: Record<StaffQuality, string> = {
  REGULAR: 'Regular',
  BOM: 'Bom',
  EXCELENTE: 'Excelente',
}

const ROLES: StaffRole[] = ['ASSISTENTE', 'PREPARADOR_FISICO', 'OLHEIRO', 'FISIOTERAPEUTA']

export function StaffPage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''
  const [browsingRole, setBrowsingRole] = useState<StaffRole | null>(null)

  const enabled = Boolean(saveGameId && clubId && token)
  const roster = useQuery({
    queryKey: ['staffRoster', saveGameId, clubId],
    queryFn: () => getStaffRoster(saveGameId, clubId, token ?? ''),
    enabled,
  })

  const candidates = useQuery({
    queryKey: ['staffCandidates', saveGameId, clubId, browsingRole],
    queryFn: () => getStaffCandidates(saveGameId, clubId, browsingRole!, token ?? ''),
    enabled: enabled && browsingRole !== null,
  })

  const hireMut = useMutation({
    mutationFn: (candidateIndex: number) =>
      hireStaff(saveGameId, clubId, browsingRole!, candidateIndex, token ?? ''),
    onSuccess: () => {
      setBrowsingRole(null)
      void queryClient.invalidateQueries({ queryKey: ['staffRoster', saveGameId, clubId] })
    },
  })

  if (roster.isLoading) return <Typography>Carregando comissão técnica…</Typography>
  if (roster.error) return <Typography color="error">Erro ao carregar comissão técnica.</Typography>

  const holderFor = (role: StaffRole): StaffMember | null => {
    switch (role) {
      case 'ASSISTENTE': return roster.data?.assistente ?? null
      case 'PREPARADOR_FISICO': return roster.data?.preparadorFisico ?? null
      case 'OLHEIRO': return roster.data?.olheiro ?? null
      case 'FISIOTERAPEUTA': return roster.data?.fisioterapeuta ?? null
    }
  }

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Comissão Técnica</Typography>
      <Stack spacing={1.5}>
        {ROLES.map(role => {
          const holder = holderFor(role)
          return (
            <Card key={role} variant="outlined" data-testid={`staff-role-${role}`}>
              <CardContent>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Box>
                    <Typography variant="overline" color="text.secondary">{ROLE_LABEL[role]}</Typography>
                    {holder ? (
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                        <Typography sx={{ fontWeight: 600 }}>{holder.name}</Typography>
                        <Chip size="small" label={QUALITY_LABEL[holder.quality]} />
                        <Typography variant="body2" color="text.secondary">{formatBRL(holder.wage)}/mês</Typography>
                      </Stack>
                    ) : (
                      <Typography color="text.secondary" sx={{ mt: 0.5 }}>Vaga em aberto</Typography>
                    )}
                  </Box>
                  <Button variant="outlined" onClick={() => setBrowsingRole(role)}>
                    {holder ? 'Substituir' : 'Contratar'}
                  </Button>
                </Stack>
              </CardContent>
            </Card>
          )
        })}
      </Stack>

      <Dialog open={browsingRole !== null} onClose={() => setBrowsingRole(null)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {browsingRole ? ROLE_LABEL[browsingRole] : ''}
          <IconButton onClick={() => setBrowsingRole(null)} size="small"><CloseIcon fontSize="small" /></IconButton>
        </DialogTitle>
        <DialogContent>
          {hireMut.isError && <Alert severity="error" sx={{ mb: 2 }}>Não foi possível contratar.</Alert>}
          {candidates.isLoading && <Typography>Carregando candidatos…</Typography>}
          <List>
            {(candidates.data ?? []).map(c => (
              <ListItemButton
                key={c.index}
                disabled={hireMut.isPending}
                onClick={() => hireMut.mutate(c.index)}
                aria-label={c.name}
              >
                <ListItemText
                  primary={c.name}
                  secondary={`${QUALITY_LABEL[c.quality]} · ${formatBRL(c.wage)}/mês`}
                />
              </ListItemButton>
            ))}
          </List>
        </DialogContent>
      </Dialog>
    </Box>
  )
}
