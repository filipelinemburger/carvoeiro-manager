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
 *
 * `disabled`, when true, makes the whole card non-interactive (no click, no keyboard activation)
 * — `MatchScoreboard` sets it on the own-club card at full time, since neither of its entry
 * points (Substituir/Tática) is meaningful once the match is over.
 */
export function ClubCard({ shortName, colors, caption, isOwn, onClick, clash = false, disabled = false }: {
  shortName: string
  colors: LiveKitColors | null
  caption?: string
  isOwn: boolean
  onClick: () => void
  clash?: boolean
  disabled?: boolean
}) {
  const { bg, fg } = cardColors(colors)

  return (
    <ButtonBase
      data-testid={isOwn ? 'club-card-own' : 'club-card-opponent'}
      aria-label={isOwn ? `Ações do meu time — ${shortName}` : `Escalação do adversário — ${shortName}`}
      onClick={onClick}
      disabled={disabled}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        // One of the scoreboard's three equal columns (home card | score | away card), not a
        // pill hugging its label: grow into the free space, and keep no minimum so a long short
        // name can't push the row wider than the screen.
        flex: 1,
        minWidth: 0,
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
