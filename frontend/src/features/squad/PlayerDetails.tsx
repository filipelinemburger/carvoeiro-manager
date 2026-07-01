import { useEffect, useState, type ReactNode } from 'react'
import {
  Dialog, DialogTitle, DialogContent, IconButton, Box, Stack, Typography,
  Chip, LinearProgress, Tabs, Tab,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import { formatBRL } from '../../lib/money'
import type { PlayerResponse } from '../../api/types'

const POSITION_LABEL: Record<PlayerResponse['position'], string> = {
  GK: 'Goleiro', DEF: 'Defensor', MID: 'Meio-campo', ATT: 'Atacante',
}

// Attribute rows shown as labeled bars, in a scouting-style order.
const ATTRIBUTES: ReadonlyArray<{ key: keyof PlayerResponse; label: string }> = [
  { key: 'pace', label: 'Ritmo' },
  { key: 'finishing', label: 'Finalização' },
  { key: 'passing', label: 'Passe' },
  { key: 'tackling', label: 'Desarme' },
  { key: 'goalkeeping', label: 'Goleiro' },
  { key: 'stamina', label: 'Fôlego' },
]

function formLabel(form: number): string {
  if (form > 0) return `▲ +${form}`
  if (form < 0) return `▼ ${form}`
  return '–'
}

/** A labeled attribute bar (0–100 scale). */
function AttrBar({ label, value }: { label: string; value: number }) {
  return (
    <Box>
      <Stack direction="row" sx={{ justifyContent: 'space-between', mb: 0.25 }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>{label}</Typography>
        <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
      </Stack>
      <LinearProgress variant="determinate" value={Math.min(100, Math.max(0, value))} sx={{ height: 6, borderRadius: 3 }} />
    </Box>
  )
}

/** A small "LABEL / value" stat. */
function Stat({ label, value, color }: { label: string; value: ReactNode; color?: string }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ display: 'block', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', color }}>{value}</Typography>
    </Box>
  )
}

/**
 * A modal breakdown of one player. Identity (position/OVR/age/status) stays pinned
 * at the top; the rest splits into an "Atributos" tab (attribute bars) and a
 * "Contrato" tab (contract term + wage). A red dot marks the Contrato tab when the
 * deal is running out (<= 1 year).
 *
 * `lineupControl` (the role selector) and `contractAction` (Renovar) render when
 * provided, so compact layouts can host those controls here.
 */
export function PlayerDetails({ player, onClose, lineupControl, contractAction }: {
  player: PlayerResponse | null
  onClose: () => void
  lineupControl?: ReactNode
  contractAction?: ReactNode
}) {
  const [tab, setTab] = useState(0)
  const contractEnding = (player?.contractYears ?? 99) <= 1

  // Reset to the Atributos tab whenever a different player opens.
  useEffect(() => { setTab(0) }, [player?.id])

  return (
    <Dialog open={Boolean(player)} onClose={onClose} fullWidth maxWidth="xs">
      {player && (
        <>
          <DialogTitle sx={{ pr: 6, fontWeight: 700 }}>
            {player.name}
            <IconButton aria-label="Fechar" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}>
              <CloseIcon />
            </IconButton>
          </DialogTitle>
          <DialogContent dividers sx={{ pt: 1.5 }}>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
              <Chip size="small" label={POSITION_LABEL[player.position]} sx={{ bgcolor: '#222', color: '#fff', fontWeight: 700 }} />
              <Chip size="small" color="primary" label={`OVR ${player.overall}`} sx={{ fontWeight: 800 }} />
              <Chip size="small" variant="outlined" label={`${player.age} anos`} />
              {player.suspensionRemaining > 0 && (
                <Chip size="small" color="error" label={`Suspenso ${player.suspensionRemaining}`} />
              )}
              {player.injuryMatches > 0 && (
                <Chip size="small" color="error" variant="outlined" label={`Lesionado ${player.injuryMatches}`} />
              )}
            </Stack>

            {lineupControl && (
              <Box sx={{ mb: 1.5 }}>
                <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Função</Typography>
                {lineupControl}
              </Box>
            )}

            <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="fullWidth" sx={{ mb: 2, minHeight: 40 }}>
              <Tab label="Atributos" sx={{ minHeight: 40 }} />
              <Tab
                sx={{ minHeight: 40 }}
                label={
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                    <span>Contrato</span>
                    {contractEnding && (
                      <Box
                        title="Contrato terminando"
                        sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'error.main' }}
                      />
                    )}
                  </Stack>
                }
              />
            </Tabs>

            {tab === 0 && (
              <Box>
                <Box sx={{ mb: 1.5 }}>
                  <Stat label="Forma" value={formLabel(player.form)} />
                </Box>
                <Stack spacing={1}>
                  {ATTRIBUTES.map(attr => (
                    <AttrBar key={attr.key} label={attr.label} value={player[attr.key] as number} />
                  ))}
                </Stack>
              </Box>
            )}

            {tab === 1 && (
              <Stack spacing={2}>
                <Stat
                  label="Contrato"
                  value={`${player.contractYears} ano(s)`}
                  color={contractEnding ? 'error.main' : undefined}
                />
                <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
                  <Stat label="Salário pedido" value={formatBRL(player.wageDemand)} />
                  {contractAction}
                </Stack>
              </Stack>
            )}
          </DialogContent>
        </>
      )}
    </Dialog>
  )
}
