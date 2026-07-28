import { useEffect, useState, type ReactNode } from 'react'
import {
  Dialog, DialogTitle, DialogContent, IconButton, Box, Stack, Typography,
  Chip, Tabs, Tab,
} from '@mui/material'
import CloseIcon from '@mui/icons-material/Close'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { getPlayerStats } from '../../api/playerStats'
import { formatBRL } from '../../lib/money'
import { moraleMood } from './morale'
import { LISTED_REASON_LABEL } from './listedReason'
import { CARBON, ovrBadgeSx } from '../../muiTheme'
import type { PlayerResponse, AttributeView, RoleArchetype } from '../../api/types'
import { positionLabel, DETAILED_POSITION_SHORT } from '../players/positionLabels'
import { ARCHETYPE_LABEL, parseFamiliarRoles } from '../players/roleLabels'

const POSITION_LABEL: Record<PlayerResponse['position'], string> = {
  GK: 'Goleiro', DEF: 'Defensor', MID: 'Meio-campo', ATT: 'Atacante',
}

// Attribute-board columns, in display order (matches a Championship-Manager profile).
const GROUP_ORDER: AttributeView['group'][] = ['TECNICO', 'MENTAL', 'FISICO', 'GOLEIRO']
const GROUP_LABEL: Record<AttributeView['group'], string> = {
  TECNICO: 'Técnico', MENTAL: 'Mental', FISICO: 'Físico', GOLEIRO: 'Goleiro',
}

/**
 * "PE · ME"-style short-label line for a player's positions (primary first, then `altPositions`,
 * deduplicated). `null` when the player has no `detailedPosition` at all (legacy payload) — the
 * fallback coarse label already shows next to "Pos" in that case, so this line just disappears.
 */
function positionsShortLabel(player: PlayerResponse): string | null {
  if (!player.detailedPosition) return null
  const positions = [player.detailedPosition, ...(player.altPositions ?? [])]
  return Array.from(new Set(positions)).map(p => DETAILED_POSITION_SHORT[p]).join(' · ')
}

/**
 * "Atacante por dentro ★★, Ponta ★"-style line for a player's familiar role archetypes, parsed
 * from `PlayerResponse.familiarRoles` via the shared `parseFamiliarRoles` helper (★★ for a "++"
 * token, ★ for a lone "+"). `null` when the player has no familiar roles at all.
 */
function familiarRolesLabel(player: PlayerResponse): string | null {
  const entries = Object.entries(parseFamiliarRoles(player.familiarRoles)) as [RoleArchetype, number][]
  if (entries.length === 0) return null
  return entries.map(([archetype, level]) => `${ARCHETYPE_LABEL[archetype]} ${'★'.repeat(level)}`).join(', ')
}

function formLabel(form: number): string {
  if (form > 0) return `▲ +${form}`
  if (form < 0) return `▼ ${form}`
  return '–'
}

/** Colour-tiered pill background for a 0–99 attribute value (higher is better). */
function attrBadgeSx(value: number) {
  if (value >= 75) return { bgcolor: 'rgba(16,185,129,0.18)', color: CARBON.pitchGreen, border: `1px solid ${CARBON.pitchGreen}66` }
  if (value >= 55) return { bgcolor: 'rgba(16,185,129,0.10)', color: '#6EE7B7', border: `1px solid ${CARBON.pitchGreen}33` }
  if (value >= 40) return { bgcolor: CARBON.bg600, color: '#E5E7EB', border: '1px solid transparent' }
  return { bgcolor: 'rgba(239,68,68,0.14)', color: CARBON.alertRed, border: `1px solid ${CARBON.alertRed}55` }
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

/** One "label ..... value" attribute row; the value renders as a colour-coded pill. */
function AttrRow({ attr }: { attr: AttributeView }) {
  return (
    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', gap: 1, py: 0.3 }}>
      <Typography variant="body2" sx={{ color: 'text.secondary', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {attr.label}
      </Typography>
      <Box
        component="span"
        sx={{
          minWidth: 30, textAlign: 'center', px: 0.75, py: 0.1, borderRadius: 1,
          fontSize: '0.78rem', fontWeight: 800, fontVariantNumeric: 'tabular-nums',
          ...attrBadgeSx(attr.value),
        }}
      >
        {attr.value}
      </Box>
    </Stack>
  )
}

/**
 * A modal breakdown of one player. Identity (position/OVR/age/status) stays pinned at the top;
 * the "Atributos" tab shows the Championship-Manager-style board (Técnico / Mental / Físico /
 * Goleiro columns), "Contrato" shows the contract term + wage, and "Estatísticas" shows
 * season-by-season matches/minutes/goals/assists/fouls/cards plus career totals (fetched lazily,
 * only once that tab is opened). A red dot marks the Contrato tab when the deal is running out
 * (<= 1 year).
 *
 * `lineupControl` (the role selector), `instructionControl` (the personal-instruction selector),
 * `contractAction` (Renovar), and `listingAction` (list/unlist toggle) render when provided.
 * `watchAction` (the Observados star) renders in the title next to the name, but only when
 * `isOwnPlayer` is not true — the caller only passes it for rival players.
 */
export function PlayerDetails({ player, onClose, saveGameId, lineupControl, instructionControl, contractAction, listingAction, isOwnPlayer, watchAction }: {
  player: PlayerResponse | null
  onClose: () => void
  saveGameId: string
  lineupControl?: ReactNode
  instructionControl?: ReactNode
  contractAction?: ReactNode
  listingAction?: ReactNode
  isOwnPlayer?: boolean
  watchAction?: ReactNode
}) {
  const [tab, setTab] = useState(0)
  const { token } = useAuth()
  const contractEnding = (player?.contractYears ?? 99) <= 1

  // Reset to the Atributos tab whenever a different player opens.
  useEffect(() => { setTab(0) }, [player?.id])

  const attributes = player?.attributes ?? []
  const positionsShort = player ? positionsShortLabel(player) : null
  const rolesLabel = player ? familiarRolesLabel(player) : null

  const statsQuery = useQuery({
    queryKey: ['playerStats', saveGameId, player?.id],
    queryFn: () => getPlayerStats(saveGameId, player!.id, token ?? ''),
    enabled: tab === 2 && Boolean(saveGameId && player?.id && token),
  })

  return (
    <Dialog open={Boolean(player)} onClose={onClose} fullWidth maxWidth="md">
      {player && (
        <>
          <DialogTitle sx={{ pr: 6, display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Typography component="span" variant="h6" sx={{ fontWeight: 700 }}>{player.name}</Typography>
            {/* Belt-and-suspenders: never show the watch star for an own-squad player even if
                the caller's watchAction/isOwnPlayer pairing ever drifts out of sync. */}
            {!isOwnPlayer && watchAction}
            <IconButton aria-label="Fechar" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}>
              <CloseIcon />
            </IconButton>
          </DialogTitle>
          <DialogContent dividers sx={{ pt: 1.5, borderColor: CARBON.bg700 }}>
            <Stack direction="row" spacing={3} useFlexGap sx={{ flexWrap: 'wrap', mb: 1.5 }}>
              <Stat label="Pos" value={positionLabel(player, POSITION_LABEL)} />
              <Box aria-label={`OVR ${player.overall}`}>
                <Typography variant="caption" sx={{ display: 'block', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'text.secondary' }}>OVR</Typography>
                <Box component="span" sx={{ display: 'inline-block', mt: 0.25, minWidth: 34, textAlign: 'center', px: 1, py: 0.25, borderRadius: 1, fontWeight: 800, ...ovrBadgeSx(player.overall) }}>
                  {player.overall}
                </Box>
              </Box>
              <Stat label="Forma" value={formLabel(player.form)} />
              <Stat label="Moral" value={`${moraleMood(player.morale).emoji} ${moraleMood(player.morale).label}`} />
              <Stat label="Fôlego" value={player.stamina} />
              <Stat label="Condição" value={`${player.fitness}%`} />
            </Stack>

            {(positionsShort || rolesLabel) && (
              <Stack spacing={0.75} sx={{ mb: 1.5 }}>
                {positionsShort && (
                  <Box>
                    <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Posições</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{positionsShort}</Typography>
                  </Box>
                )}
                {rolesLabel && (
                  <Box>
                    <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Funções conhecidas</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{rolesLabel}</Typography>
                  </Box>
                )}
              </Stack>
            )}

            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
              <Chip size="small" variant="outlined" label={`${player.age} anos`} />
              {player.suspensionRemaining > 0 && (
                <Chip size="small" color="error" label={`Suspenso ${player.suspensionRemaining}`} />
              )}
              {player.injuryMatches > 0 && (
                <Chip size="small" color="error" variant="outlined" label={`Lesionado ${player.injuryMatches}`} />
              )}
              {player.transferListed && (
                <Chip
                  size="small"
                  color="warning"
                  label={
                    player.listedReason && player.listedReason !== 'OWNER'
                      ? `Listado — ${LISTED_REASON_LABEL[player.listedReason]}`
                      : 'Listado'
                  }
                />
              )}
            </Stack>

            {lineupControl && (
              <Box sx={{ mb: 1.5 }}>
                <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Escalação</Typography>
                {lineupControl}
              </Box>
            )}

            {instructionControl && (
              <Box sx={{ mb: 1.5 }}>
                <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 0.25 }}>Instrução pessoal</Typography>
                {instructionControl}
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
              <Tab label="Estatísticas" sx={{ minHeight: 40 }} />
            </Tabs>

            {tab === 0 && (
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
            )}

            {tab === 1 && (
              <Box sx={{ bgcolor: 'background.paper', border: '1px solid', borderColor: CARBON.bg700, borderRadius: 2, p: 2 }}>
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
                  {listingAction && (
                    <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        {player.transferListed ? 'Este jogador está listado para transferência.' : 'Jogador não está listado para transferência.'}
                      </Typography>
                      {listingAction}
                    </Stack>
                  )}
                </Stack>
              </Box>
            )}

            {tab === 2 && (
              <Box>
                {statsQuery.isLoading && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>Carregando…</Typography>
                )}
                {statsQuery.data && statsQuery.data.seasons.length === 0 && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Nenhuma partida registrada ainda.
                  </Typography>
                )}
                {statsQuery.data && statsQuery.data.seasons.length > 0 && (
                  <Box sx={{ overflowX: 'auto' }}>
                    <Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
                      <Box component="thead">
                        <Box component="tr">
                          {['Temporada', 'Competição', 'J', 'Min', 'G', 'A', 'F', 'CA', 'CV'].map(h => (
                            <Box component="th" key={h} sx={{ textAlign: 'left', py: 0.5, borderBottom: '2px solid', borderColor: 'divider', color: 'text.secondary', fontWeight: 700 }}>
                              {h}
                            </Box>
                          ))}
                        </Box>
                      </Box>
                      <Box component="tbody">
                        {statsQuery.data.seasons.map((s, i) => (
                          <Box component="tr" key={`${s.seasonYear}-${s.competitionName}-${i}`}>
                            <Box component="td" sx={{ py: 0.5 }}>{s.seasonYear}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.competitionName}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.matchesPlayed}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.minutesPlayed}</Box>
                            <Box component="td" sx={{ py: 0.5, fontWeight: 700 }}>{s.goals}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.assists}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.fouls}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.yellowCards}</Box>
                            <Box component="td" sx={{ py: 0.5 }}>{s.redCards}</Box>
                          </Box>
                        ))}
                        <Box component="tr" sx={{ borderTop: '2px solid', borderColor: 'divider' }}>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }} colSpan={2}>Total</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.matchesPlayed}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.minutesPlayed}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.goals}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.assists}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.fouls}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.yellowCards}</Box>
                          <Box component="td" sx={{ py: 0.5, fontWeight: 800 }}>{statsQuery.data.totals.redCards}</Box>
                        </Box>
                      </Box>
                    </Box>
                  </Box>
                )}
              </Box>
            )}
          </DialogContent>
        </>
      )}
    </Dialog>
  )
}
