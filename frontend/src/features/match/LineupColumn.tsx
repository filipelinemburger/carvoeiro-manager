import { Box, Link, Stack, Typography } from '@mui/material'
import type { SideView } from './matchSides'
import { POSITION_LABEL } from './PlayerRatingsList'

type OnPlayerClick = (playerId: string, isOwnPlayer: boolean) => void

/**
 * One club's lineup column: short name, formation caption, then each player's position, name
 * (optionally clickable) and overall. Used by `LineupsTab` for the home/away columns flanking
 * the pitch — `side` comes straight from `sidesOf` (`matchSides.ts`), never a hand-rolled
 * home/away mapping.
 */
export function LineupColumn({ side, onPlayerClick }: { side: SideView; onPlayerClick?: OnPlayerClick }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography sx={{ fontWeight: 700 }}>{side.shortName}</Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>{side.formation}</Typography>
      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {side.players.map(p => (
          <Stack key={p.playerId} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography sx={{ minWidth: 30, color: 'text.secondary', fontSize: '0.8rem' }}>
              {POSITION_LABEL[p.position] ?? p.position}
            </Typography>
            {onPlayerClick ? (
              <Link
                component="button"
                type="button"
                underline="hover"
                color="inherit"
                onClick={() => onPlayerClick(p.playerId, side.isOwnSide)}
                sx={{
                  flex: 1, minWidth: 0, font: 'inherit', textAlign: 'left', cursor: 'pointer',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}
              >
                {p.name}
              </Link>
            ) : (
              <Typography sx={{ flex: 1, minWidth: 0 }} noWrap>{p.name}</Typography>
            )}
            <Typography sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>{p.overall}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
