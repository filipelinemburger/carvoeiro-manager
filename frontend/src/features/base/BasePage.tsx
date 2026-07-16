import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material'
import { listProspects, promoteProspect, releaseProspect } from '../../api/youth'
import { useAuth } from '../../auth/AuthContext'
import { useIsMobile } from '../../lib/useIsMobile'
import { PotentialStars } from './PotentialStars'
import type { YouthProspectResponse } from '../../api/types'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Ação falhou.'
}

const POSITION_LABEL: Record<YouthProspectResponse['position'], string> = {
  GK: 'GOL',
  DEF: 'ZAG',
  MID: 'MEI',
  ATT: 'ATA',
}

export function BasePage() {
  const { token } = useAuth()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const isMobile = useIsMobile()
  const saveGameId = searchParams.get('saveGameId') ?? ''
  const clubId = searchParams.get('clubId') ?? ''

  const { data, isLoading, error } = useQuery({
    queryKey: ['youth', saveGameId],
    queryFn: () => listProspects(saveGameId, token ?? ''),
    enabled: Boolean(saveGameId && token),
    staleTime: Infinity,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['youth', saveGameId] })
    queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, clubId] })
  }

  const promote = useMutation({
    mutationFn: (prospectId: string) => promoteProspect(saveGameId, prospectId, token ?? ''),
    onSuccess: invalidate,
  })
  const release = useMutation({
    mutationFn: (prospectId: string) => releaseProspect(saveGameId, prospectId, token ?? ''),
    onSuccess: invalidate,
  })
  const busy = promote.isPending || release.isPending

  const onPromote = (p: YouthProspectResponse) => {
    if (window.confirm(`Promover ${p.name} ao elenco principal? Ele assinará um contrato de 3 anos.`)) {
      promote.mutate(p.id)
    }
  }
  const onRelease = (p: YouthProspectResponse) => {
    if (window.confirm(`Liberar ${p.name} da base? Esta ação não pode ser desfeita.`)) {
      release.mutate(p.id)
    }
  }

  if (isLoading) return <Typography sx={{ p: 3 }}>Carregando a base…</Typography>
  if (error) return <Typography sx={{ p: 3 }} color="error">Erro ao carregar a base.</Typography>

  const prospects = data ?? []

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 2 }}>Base</Typography>

      {(promote.isError || release.isError) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {promote.isError ? errorMessage(promote.error) : errorMessage(release.error)}
        </Alert>
      )}

      {prospects.length === 0 ? (
        <Alert severity="info">Sua base ainda não tem jovens — eles chegam a cada temporada.</Alert>
      ) : isMobile ? (
        <Stack spacing={1.5}>
          {prospects.map((p) => (
            <Card key={p.id} variant="outlined">
              <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 600 }}>{p.name}</Typography>
                  </Box>
                  <Chip size="small" label={POSITION_LABEL[p.position]} />
                  <Chip size="small" color="primary" label={`OVR ${p.overall}`} />
                </Stack>
                <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography variant="caption" color="text.secondary">{p.age} anos</Typography>
                    <PotentialStars stars={p.potentialStars} />
                  </Stack>
                  <Stack direction="row" spacing={1}>
                    <Button size="small" variant="contained" disabled={busy} onClick={() => onPromote(p)}>
                      Promover
                    </Button>
                    <Button size="small" variant="outlined" color="error" disabled={busy} onClick={() => onRelease(p)}>
                      Liberar
                    </Button>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Nome</TableCell>
                <TableCell align="center">Pos</TableCell>
                <TableCell align="center">Idade</TableCell>
                <TableCell align="center">OVR</TableCell>
                <TableCell align="center">Potencial</TableCell>
                <TableCell align="right">Ações</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {prospects.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>{p.name}</TableCell>
                  <TableCell align="center">{POSITION_LABEL[p.position]}</TableCell>
                  <TableCell align="center">{p.age}</TableCell>
                  <TableCell align="center">{p.overall}</TableCell>
                  <TableCell align="center"><PotentialStars stars={p.potentialStars} /></TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                      <Button size="small" variant="contained" disabled={busy} onClick={() => onPromote(p)}>
                        Promover
                      </Button>
                      <Button size="small" variant="outlined" color="error" disabled={busy} onClick={() => onRelease(p)}>
                        Liberar
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}
