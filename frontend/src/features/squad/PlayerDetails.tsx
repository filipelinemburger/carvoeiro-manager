import { useEffect, useState, type ReactNode } from 'react'
import {
  Dialog, DialogTitle, DialogContent, IconButton, Box, Stack, Typography,
  Chip, Tabs, Tab,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import { formatBRL } from '../../lib/money'
import { moraleMood } from './morale'
import type { PlayerResponse, AttributeView } from '../../api/types'

const POSITION_LABEL: Record<PlayerResponse['position'], string> = {
  GK: 'Goleiro', DEF: 'Defensor', MID: 'Meio-campo', ATT: 'Atacante',
}

// Attribute-board columns, in display order (matches a Championship-Manager profile).
const GROUP_ORDER: AttributeView['group'][] = ['TECNICO', 'MENTAL', 'FISICO', 'GOLEIRO']
const GROUP_LABEL: Record<AttributeView['group'], string> = {
  TECNICO: 'Técnico', MENTAL: 'Mental', FISICO: 'Físico', GOLEIRO: 'Goleiro',
}

function formLabel(form: number): string {
  if (form > 0) return `▲ +${form}`
  if (form < 0) return `▼ ${form}`
  return '–'
}

/** Colour a 0–99 attribute value on a green→red scale (higher is better). */
function attrColor(value: number): string {
  if (value >= 75) return '#1b5e20'
  if (value >= 55) return '#2e7d32'
  if (value >= 40) return 'text.primary'
  return '#c62828'
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

/** One "label ..... value" attribute row. */
function AttrRow({ attr }: { attr: AttributeView }) {
  return (
    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'baseline', gap: 1, py: 0.15 }}>
      <Typography variant="body2" sx={{ color: 'text.secondary', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {attr.label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums', color: attrColor(attr.value) }}>
        {attr.value}
      </Typography>
    </Stack>
  )
}

/**
 * A modal breakdown of one player. Identity (position/OVR/age/status) stays pinned at the top;
 * the "Atributos" tab shows the Championship-Manager-style board (Técnico / Mental / Físico /
 * Goleiro columns), and "Contrato" shows the contract term + wage. A red dot marks the Contrato
 * tab when the deal is running out (<= 1 year).
 *
 * `lineupControl` (the role selector) and `contractAction` (Renovar) render when provided.
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

  const attributes = player?.attributes ?? []

  return (
    <Dialog open={Boolean(player)} onClose={onClose} fullWidth maxWidth="md">
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
                <Stack direction="row" spacing={3} useFlexGap sx={{ flexWrap: 'wrap', mb: 2 }}>
                  <Stat label="Forma" value={formLabel(player.form)} />
                  <Stat label="Moral" value={`${moraleMood(player.morale).emoji} ${moraleMood(player.morale).label}`} />
                  <Stat label="Fôlego" value={player.stamina} />
                  <Stat label="Condição" value={`${player.fitness}%`} />
                </Stack>
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, 1fr)', sm: 'repeat(4, 1fr)' }, gap: 2.5 }}>
                  {GROUP_ORDER.map(group => {
                    const rows = attributes.filter(a => a.group === group)
                    if (rows.length === 0) return null
                    return (
                      <Box key={group}>
                        <Typography variant="subtitle2" sx={{ fontWeight: 800, mb: 0.5, pb: 0.25, borderBottom: '2px solid', borderColor: 'divider' }}>
                          {GROUP_LABEL[group]}
                        </Typography>
                        {rows.map(a => <AttrRow key={a.key} attr={a} />)}
                      </Box>
                    )
                  })}
                </Box>
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
