import { ButtonBase, Typography } from '@mui/material'
import { cardColors } from './clubKit'
import type { LiveKitColors } from '../../api/types'

/**
 * One clickable club card — the scoreboard's entry point into that club's actions (own club:
 * substitutions/tactics; opponent: read-only scouting). Colours come from `cardColors`
 * (`clubKit.ts`), which already guards against the null/non-hex kit values the backend can send.
 *
 * `clash` is a plain boolean handed down by `MatchScoreboard`: this component never compares
 * itself against the other club's kit, it just draws an outline when told to.
 */
export function ClubCard({ shortName, colors, caption, isOwn, onClick, clash = false }: {
  shortName: string
  colors: LiveKitColors | null
  caption?: string
  isOwn: boolean
  onClick: () => void
  clash?: boolean
}) {
  const { bg, fg } = cardColors(colors)

  return (
    <ButtonBase
      data-testid={isOwn ? 'club-card-own' : 'club-card-opponent'}
      aria-label={isOwn ? `Ações do meu time — ${shortName}` : `Escalação do adversário — ${shortName}`}
      onClick={onClick}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minWidth: 100,
        px: 2,
        py: 1,
        borderRadius: 1.5,
        bgcolor: bg,
        color: fg,
        ...(clash ? { border: `1px solid ${fg}` } : {}),
      }}
    >
      <Typography sx={{ fontWeight: 700, color: fg, lineHeight: 1.2 }}>{shortName}</Typography>
      {caption && (
        <Typography variant="caption" sx={{ color: fg, lineHeight: 1.2 }}>{caption}</Typography>
      )}
    </ButtonBase>
  )
}
