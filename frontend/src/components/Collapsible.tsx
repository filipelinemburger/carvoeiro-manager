import { type ReactNode } from 'react'
import { Accordion, AccordionSummary, AccordionDetails, Typography } from '@mui/material'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'

/**
 * A titled, expand/collapse section built on MUI's Accordion. The summary is a
 * button exposing aria-expanded; the body mounts eagerly so content inside stays
 * queryable even while collapsed.
 */
export function Collapsible({ title, defaultOpen = true, children }: {
  title: string
  defaultOpen?: boolean
  children: ReactNode
}) {
  return (
    <Accordion
      defaultExpanded={defaultOpen}
      variant="outlined"
      disableGutters
      sx={{ mb: 2, borderRadius: 2, '&:before': { display: 'none' } }}
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Typography sx={{ fontWeight: 700, fontSize: '1.1rem' }}>{title}</Typography>
      </AccordionSummary>
      <AccordionDetails>{children}</AccordionDetails>
    </Accordion>
  )
}
