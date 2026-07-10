import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Autocomplete, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField,
} from '@mui/material'
import { useAuth } from '../../auth/AuthContext'
import { createCareer } from '../../api/career'
import { saveGameKeyFor } from './saveGameKey'
import type { SaveGameResponse } from '../../api/types'

type Props = { open: boolean; onClose: () => void }

const COUNTRIES = [
  'Brasil', 'Argentina', 'Uruguai', 'Paraguai', 'Chile', 'Colômbia',
  'Portugal', 'Espanha', 'Itália', 'França', 'Alemanha', 'Inglaterra',
]

/** Collects the manager's name and nationality before starting a fresh career. */
export function NewCareerDialog({ open, onClose }: Props) {
  const { user, token } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [managerName, setManagerName] = useState('')
  const [managerNationality, setManagerNationality] = useState<string | null>(null)

  const mutation = useMutation<SaveGameResponse>({
    mutationFn: () => createCareer({ managerName, managerNationality: managerNationality ?? '' }, token ?? ''),
    onSuccess: (data) => {
      localStorage.setItem(saveGameKeyFor(user?.userId), data.id)
      void queryClient.invalidateQueries()
      onClose()
      void navigate(`/squad?saveGameId=${data.id}&clubId=${data.managedClubId}`)
    },
  })

  const canSubmit = managerName.trim().length > 0 && Boolean(managerNationality)

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Nova carreira</DialogTitle>
      <DialogContent dividers>
        <TextField
          label="Nome do técnico"
          fullWidth
          margin="normal"
          value={managerName}
          onChange={e => setManagerName(e.target.value)}
        />
        <Autocomplete
          options={COUNTRIES}
          value={managerNationality}
          onChange={(_, value) => setManagerNationality(value)}
          renderInput={(params) => <TextField {...params} label="Nacionalidade" margin="normal" />}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button
          variant="contained"
          disabled={!canSubmit || mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? 'Criando…' : 'Iniciar carreira'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
