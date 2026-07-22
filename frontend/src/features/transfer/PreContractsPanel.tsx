import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  Link, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import {
  acceptPreContractCounter, getPreContractAgreements, getPreContractEligible,
  makePreContractOffer, withdrawPreContract,
} from '../../api/precontract'
import { getPlayer, renewContract } from '../../api/career'
import { errorMessage } from '../../api/errorMessages'
import { PlayerDetails } from '../squad/PlayerDetails'
import { WatchStar } from '../players/WatchStar'
import type { PreContractAgreement, PreContractEligible } from '../../api/types'
import { formatBRL } from '../../lib/money'

const STATUS_LABEL: Record<string, string> = {
  AGREED: 'Acordado',
  LOCKED: 'Fechado',
  CANCELLED: 'Cancelado',
}

const STATUS_COLOR: Record<string, 'warning' | 'success' | 'default'> = {
  AGREED: 'warning',
  LOCKED: 'success',
  CANCELLED: 'default',
}

type Props = {
  saveGameId: string
  token: string
  managedClubId: string
  windowOpen: boolean
}

export default function PreContractsPanel({ saveGameId, token, managedClubId, windowOpen }: Props) {
  const queryClient = useQueryClient()
  const [target, setTarget] = useState<PreContractEligible | null>(null)
  const [wage, setWage] = useState('')
  const [years, setYears] = useState(3)
  const [counterWage, setCounterWage] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  // The eligible player whose read-only details (+ watch star) are open, if any.
  const [selectedPlayerId, setSelectedPlayerId] = useState<string | null>(null)

  const enabled = Boolean(saveGameId && token)
  const selectedPlayer = useQuery({
    queryKey: ['player', saveGameId, selectedPlayerId],
    queryFn: () => getPlayer(saveGameId, selectedPlayerId!, token),
    enabled: enabled && Boolean(selectedPlayerId),
  })
  const eligible = useQuery({
    queryKey: ['preContractEligible', saveGameId],
    queryFn: () => getPreContractEligible(saveGameId, token),
    enabled: enabled && windowOpen,
    staleTime: Infinity,
  })
  const agreements = useQuery({
    queryKey: ['preContractAgreements', saveGameId],
    queryFn: () => getPreContractAgreements(saveGameId, token),
    enabled,
    staleTime: Infinity,
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['preContractEligible', saveGameId] })
    void queryClient.invalidateQueries({ queryKey: ['preContractAgreements', saveGameId] })
  }

  const offerMut = useMutation({
    mutationFn: (v: { playerId: string; wage: number; contractYears: number }) =>
      makePreContractOffer(saveGameId, v, token),
    onSuccess: (r) => {
      if (r.status === 'COUNTERED') {
        setCounterWage(r.demandedWage)
      } else {
        setTarget(null)
        setCounterWage(null)
        invalidate()
      }
    },
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao enviar proposta.')),
  })

  const acceptCounterMut = useMutation({
    mutationFn: (v: { playerId: string; contractYears: number }) =>
      acceptPreContractCounter(saveGameId, v.playerId, { contractYears: v.contractYears }, token),
    onSuccess: () => {
      setTarget(null)
      setCounterWage(null)
      invalidate()
    },
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao aceitar contraproposta.')),
  })

  const withdrawMut = useMutation({
    mutationFn: (agreementId: string) => withdrawPreContract(saveGameId, agreementId, token),
    onSuccess: invalidate,
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao desistir do pré-contrato.')),
  })

  const defendMut = useMutation({
    mutationFn: (a: PreContractAgreement) =>
      renewContract(saveGameId, a.playerId,
        { wage: Math.ceil(a.wage * 1.05 * 100) / 100, contractYears: 3 }, token),
    onSuccess: () => {
      invalidate()
      void queryClient.invalidateQueries({ queryKey: ['squad', saveGameId, managedClubId] })
    },
    onError: (e: Error) => setError(errorMessage(e, 'Erro ao renovar contrato.')),
  })

  const threats = (agreements.data ?? []).filter(a => a.incoming)
  const outgoing = (agreements.data ?? []).filter(a => !a.incoming)

  return (
    <Box>
      {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>{error}</Alert>}

      {!windowOpen && (
        <Alert severity="info" sx={{ mb: 2 }}>
          A janela de pré-contratos abre em 15 de julho — seis meses antes dos contratos expirarem.
        </Alert>
      )}

      {agreements.isLoading && (
        <Typography sx={{ mb: 2 }}>Carregando pré-contratos…</Typography>
      )}
      {agreements.isError && (
        <Typography color="error" sx={{ mb: 2 }}>Erro ao carregar pré-contratos.</Typography>
      )}

      {!agreements.isLoading && !agreements.isError && threats.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>⚠️ Jogadores de saída</Typography>
          {threats.map(a => (
            <Stack key={a.id} direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
              <Typography>
                <strong>{a.playerName}</strong> assinou pré-contrato com {a.toClubName}{' '}
                ({formatBRL(a.wage)}/sem, {a.contractYears} anos)
              </Typography>
              <Chip size="small" color={STATUS_COLOR[a.status]} label={STATUS_LABEL[a.status]} />
              {a.status === 'AGREED' && (
                <Button size="small" variant="contained" color="warning"
                        disabled={defendMut.isPending}
                        onClick={() => defendMut.mutate(a)}>
                  Renovar para defender ({formatBRL(Math.ceil(a.wage * 1.05 * 100) / 100)})
                </Button>
              )}
            </Stack>
          ))}
        </Box>
      )}

      {!agreements.isLoading && !agreements.isError && outgoing.length > 0 && (
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Meus acordos</Typography>
          {outgoing.map(a => (
            <Stack key={a.id} direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
              <Typography>
                <strong>{a.playerName}</strong> ({a.position}, {a.age}) — vem de {a.fromClubName}{' '}
                por {formatBRL(a.wage)}/sem, {a.contractYears} anos
              </Typography>
              <Chip size="small" color={STATUS_COLOR[a.status]} label={STATUS_LABEL[a.status]} />
              {a.status === 'AGREED' && (
                <Button size="small" disabled={withdrawMut.isPending}
                        onClick={() => withdrawMut.mutate(a.id)}>
                  Desistir
                </Button>
              )}
            </Stack>
          ))}
        </Box>
      )}

      <Typography variant="h6" sx={{ mb: 1 }}>Jogadores em fim de contrato</Typography>
      {windowOpen && eligible.isLoading && (
        <Typography>Carregando jogadores em fim de contrato…</Typography>
      )}
      {windowOpen && eligible.isError && (
        <Typography color="error">Erro ao carregar jogadores em fim de contrato.</Typography>
      )}
      {windowOpen && !eligible.isLoading && !eligible.isError && (eligible.data?.length ?? 0) === 0 && (
        <Typography color="text.secondary">Nenhum jogador disponível para pré-contrato.</Typography>
      )}
      {windowOpen && !eligible.isLoading && !eligible.isError && (eligible.data?.length ?? 0) > 0 && (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Jogador</TableCell>
              <TableCell>Pos</TableCell>
              <TableCell align="right">Idade</TableCell>
              <TableCell align="right">Geral</TableCell>
              <TableCell>Clube</TableCell>
              <TableCell align="right">Pedida</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {eligible.data!.map(p => (
              <TableRow key={p.playerId} hover>
                <TableCell>
                  <Link
                    component="button"
                    type="button"
                    underline="hover"
                    color="inherit"
                    onClick={() => setSelectedPlayerId(p.playerId)}
                    sx={{ font: 'inherit', textAlign: 'left', cursor: 'pointer' }}
                  >
                    {p.name}
                  </Link>
                </TableCell>
                <TableCell>{p.position}</TableCell>
                <TableCell align="right">{p.age}</TableCell>
                <TableCell align="right">{p.overall}</TableCell>
                <TableCell>{p.clubName}</TableCell>
                <TableCell align="right">{formatBRL(p.wageDemand)}</TableCell>
                <TableCell>
                  <Button size="small" variant="outlined" onClick={() => {
                    setTarget(p)
                    setWage(String(p.wageDemand))
                    setYears(3)
                    setCounterWage(null)
                    setError(null)
                  }}>
                    Propor
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Dialog open={Boolean(target)} onClose={() => setTarget(null)} fullWidth maxWidth="xs">
        <DialogTitle>Pré-contrato — {target?.name}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Pedida do jogador: <strong>{formatBRL(target?.wageDemand ?? 0)}</strong>/semana
          </Typography>
          {counterWage != null ? (
            <Alert severity="warning" sx={{ mb: 2 }}>
              O jogador recusou, mas aceita por <strong>{formatBRL(counterWage)}</strong>/semana.
            </Alert>
          ) : (
            <TextField label="Salário semanal" type="number" fullWidth sx={{ mb: 2 }}
                       value={wage} onChange={e => setWage(e.target.value)}
                       slotProps={{ htmlInput: { min: 1 } }} />
          )}
          <TextField label="Anos de contrato" type="number" fullWidth
                     value={years}
                     onChange={e => setYears(Number(e.target.value))}
                     slotProps={{ htmlInput: { min: 1, max: 5 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTarget(null)}>Cancelar</Button>
          {counterWage != null ? (
            <Button variant="contained" disabled={acceptCounterMut.isPending}
                    onClick={() => target && acceptCounterMut.mutate({ playerId: target.playerId, contractYears: years })}>
              Aceitar contraproposta
            </Button>
          ) : (
            <Button variant="contained" disabled={offerMut.isPending || !wage}
                    onClick={() => target && offerMut.mutate({ playerId: target.playerId, wage: Number(wage), contractYears: years })}>
              Enviar proposta
            </Button>
          )}
        </DialogActions>
      </Dialog>

      <PlayerDetails
        player={selectedPlayer.data ?? null}
        onClose={() => setSelectedPlayerId(null)}
        saveGameId={saveGameId}
        isOwnPlayer={false}
        watchAction={selectedPlayer.data ? (
          <WatchStar saveGameId={saveGameId} playerId={selectedPlayer.data.id} />
        ) : undefined}
      />
    </Box>
  )
}
