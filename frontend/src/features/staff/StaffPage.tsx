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
import type { StaffRole, StaffMember } from '../../api/types'
import { STAFF_ATTRIBUTE_LABELS, STAFF_QUALITY_LABEL, STAFF_ROLE_LABEL, STAFF_ROLE_ORDER } from './staffLabels'

/** Color tier for a 1-20 staff attribute: >=16 gold, >=12 green, else grey (mirrors OVR tiers). */
function attributeChipSx(value: number) {
  if (value >= 16) return { bgcolor: 'var(--cec-yellow)', color: '#111', fontWeight: 700 }
  if (value >= 12) return { bgcolor: 'rgba(16,185,129,0.16)', color: '#10B981', fontWeight: 700 }
  return { bgcolor: 'rgba(255,255,255,0.08)', color: 'text.secondary', fontWeight: 600 }
}

function AttributeChips({ role, primary, secondary }: { role: StaffRole; primary: number; secondary: number }) {
  const [primaryLabel, secondaryLabel] = STAFF_ATTRIBUTE_LABELS[role]
  return (
    <Stack direction="row" spacing={1} sx={{ mt: 0.5, flexWrap: 'wrap', rowGap: 0.5 }}>
      <Chip size="small" label={`${primaryLabel}: ${primary}`} sx={attributeChipSx(primary)} />
      <Chip size="small" label={`${secondaryLabel}: ${secondary}`} sx={attributeChipSx(secondary)} />
    </Stack>
  )
}


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
    staleTime: Infinity,
  })

  const candidates = useQuery({
    queryKey: ['staffCandidates', saveGameId, clubId, browsingRole],
    queryFn: () => getStaffCandidates(saveGameId, clubId, browsingRole!, token ?? ''),
    enabled: enabled && browsingRole !== null,
    staleTime: Infinity,
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
      case 'TECNICO': return roster.data?.tecnico ?? null
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
        {STAFF_ROLE_ORDER.map(role => {
          const holder = holderFor(role)
          return (
            <Card key={role} variant="outlined" data-testid={`staff-role-${role}`}>
              <CardContent>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Box>
                    <Typography variant="overline" color="text.secondary">{STAFF_ROLE_LABEL[role]}</Typography>
                    {holder ? (
                      <Box>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
                          <Typography sx={{ fontWeight: 600 }}>{holder.name}</Typography>
                          {holder.quality && <Chip size="small" label={STAFF_QUALITY_LABEL[holder.quality]} />}
                          {holder.wage != null && (
                            <Typography variant="body2" color="text.secondary">{formatBRL(holder.wage)}/mês</Typography>
                          )}
                        </Stack>
                        {holder.quality && (
                          <AttributeChips role={role} primary={holder.primaryAttribute} secondary={holder.secondaryAttribute} />
                        )}
                      </Box>
                    ) : (
                      <Typography color="text.secondary" sx={{ mt: 0.5 }}>Vaga em aberto</Typography>
                    )}
                  </Box>
                  {role !== 'TECNICO' && (
                    <Button variant="outlined" onClick={() => setBrowsingRole(role)}>
                      {holder ? 'Substituir' : 'Contratar'}
                    </Button>
                  )}
                </Stack>
              </CardContent>
            </Card>
          )
        })}
      </Stack>

      <Dialog open={browsingRole !== null} onClose={() => setBrowsingRole(null)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {browsingRole ? STAFF_ROLE_LABEL[browsingRole] : ''}
          <IconButton onClick={() => setBrowsingRole(null)} size="small"><CloseIcon fontSize="small" /></IconButton>
        </DialogTitle>
        <DialogContent>
          {hireMut.isError && <Alert severity="error" sx={{ mb: 2 }}>Não foi possível contratar.</Alert>}
          {candidates.isLoading && <Typography>Carregando candidatos…</Typography>}
          {candidates.isError && <Alert severity="error" sx={{ mb: 2 }}>Erro ao carregar candidatos.</Alert>}
          {candidates.isSuccess && (candidates.data ?? []).length === 0 && (
            <Typography color="text.secondary">Nenhum candidato disponível.</Typography>
          )}
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
                  secondary={
                    <>
                      <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
                        {STAFF_QUALITY_LABEL[c.quality]} · {formatBRL(c.wage)}/mês
                      </Typography>
                      <Chip
                        size="small"
                        label={c.currentClubName ?? 'Livre'}
                        sx={{ mb: 0.5, mr: 0.5 }}
                        color={c.currentClubName ? 'warning' : 'default'}
                      />
                      <AttributeChips role={browsingRole!} primary={c.primaryAttribute} secondary={c.secondaryAttribute} />
                    </>
                  }
                  slotProps={{ secondary: { component: 'div' } }}
                />
              </ListItemButton>
            ))}
          </List>
        </DialogContent>
      </Dialog>
    </Box>
  )
}
