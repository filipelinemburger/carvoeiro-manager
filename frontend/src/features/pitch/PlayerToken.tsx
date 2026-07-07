import { Box, Typography } from '@mui/material'

export type TokenBadges = { goal?: boolean; card?: 'YELLOW' | 'RED'; injured?: boolean }

function ringColor(fitness: number): string {
  if (fitness < 40) return '#d32f2f'
  if (fitness < 70) return '#ed6c02'
  return '#2e7d32'
}

export function PlayerToken(props: {
  number: number
  surname?: string
  fill: string
  text: string
  fitness: number
  badges?: TokenBadges
}) {
  const { number, surname, fill, text, fitness, badges } = props
  const ring = ringColor(Math.max(0, Math.min(100, fitness)))
  return (
    <Box
      data-testid="player-token"
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: 46, userSelect: 'none' }}
    >
      <Box sx={{ position: 'relative', width: 30, height: 30 }}>
        <Box
          data-role="disc"
          sx={{
            width: 30, height: 30, borderRadius: '50%', backgroundColor: fill,
            border: `2px solid ${ring}`, display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 1px 2px rgba(0,0,0,0.4)',
          }}
        >
          <Typography sx={{ color: text, fontSize: 13, fontWeight: 800, lineHeight: 1 }}>{number}</Typography>
        </Box>
        {badges?.goal && (
          <Box aria-label="Gol" sx={{ position: 'absolute', top: -6, right: -6, fontSize: 13 }}>⚽</Box>
        )}
        {badges?.card && (
          <Box
            aria-label={badges.card === 'YELLOW' ? 'Cartão amarelo' : 'Cartão vermelho'}
            sx={{
              position: 'absolute', top: -5, left: -5, width: 8, height: 11, borderRadius: '1px',
              backgroundColor: badges.card === 'YELLOW' ? '#ffd200' : '#d32f2f', border: '1px solid #111',
            }}
          />
        )}
        {badges?.injured && (
          <Box aria-label="Lesionado" sx={{ position: 'absolute', bottom: -6, right: -6, color: '#d32f2f', fontSize: 13, fontWeight: 900, lineHeight: 1 }}>✚</Box>
        )}
      </Box>
      {surname && (
        <Typography sx={{ mt: 0.25, fontSize: 9, fontWeight: 600, color: '#fff', textShadow: '0 1px 2px rgba(0,0,0,0.8)', maxWidth: 46, textAlign: 'center', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {surname}
        </Typography>
      )}
    </Box>
  )
}
