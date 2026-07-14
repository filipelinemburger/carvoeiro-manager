import { createTheme } from '@mui/material/styles'
import type { SxProps, Theme } from '@mui/material'

/**
 * "Carbon" dark palette — the app-wide theme. `gold` is the Criciúma EC brand yellow,
 * kept as the accent (primary) color throughout; `pitchGreen`/`alertRed`/`amber` are the
 * semantic tiers used by stat badges (OVR, attributes, ratings) across every page.
 */
export const CARBON = {
  bg900: '#0B0C10',
  bg800: '#12141D',
  bg700: '#1E2028',
  bg600: '#2A2D39',
  gold: '#f7d417',
  pitchGreen: '#10B981',
  alertRed: '#EF4444',
  amber: '#F59E0B',
} as const

/**
 * Material UI theme carrying the Criciúma EC brand palette (yellow / near-black / green)
 * in a dark "carbon" mode. Intentionally no CssBaseline at the root — the app's stadium
 * body background lives in theme.css; the dark content panel lives in Layout.tsx.
 */
export const muiTheme = createTheme({
  palette: {
    mode: 'dark',
    background: { default: CARBON.bg900, paper: CARBON.bg800 },
    text: { primary: '#E5E7EB', secondary: '#9CA3AF' },
    divider: CARBON.bg700,
    primary: { main: CARBON.gold, contrastText: '#111111' },
    secondary: { main: '#1f7a3f', contrastText: '#ffffff' },
    success: { main: CARBON.pitchGreen },
    warning: { main: CARBON.amber, contrastText: '#111111' },
    error: { main: CARBON.alertRed },
  },
  typography: { fontFamily: 'system-ui, sans-serif' },
  shape: { borderRadius: 8 },
  components: {
    MuiButton: {
      defaultProps: { variant: 'contained', disableElevation: true },
      styleOverrides: { root: { fontWeight: 700, textTransform: 'none' } },
    },
    // Outlined-by-default keeps the flat, on-panel card look consistent across
    // every converted page without each call site repeating the variant.
    MuiCard: {
      defaultProps: { variant: 'outlined' },
    },
    // Tables sit inside the dark content panel; keep cell padding compact so
    // data-dense grids (squad, standings) breathe on desktop.
    MuiTableCell: {
      styleOverrides: { root: { paddingTop: 8, paddingBottom: 8, borderColor: CARBON.bg700 } },
    },
    MuiTableRow: {
      styleOverrides: {
        // Sticky cells (e.g. the squad table's Escalação/Nome columns) carry their own
        // opaque background so scrolled content doesn't show through them, which otherwise
        // masks this row-level hover — `.sq-sticky-cell` re-applies it with higher
        // specificity so the whole row, sticky columns included, shades together.
        root: {
          '&:hover': { backgroundColor: CARBON.bg700 },
          '&:hover .sq-sticky-cell': { backgroundColor: CARBON.bg700 },
        },
      },
    },
  },
})

/** Color-coded badge tiers (elite / good / average / poor) for an OVR rating (0-99). */
export function ovrBadgeSx(ovr: number): SxProps<Theme> {
  if (ovr >= 75) return { bgcolor: CARBON.gold, color: '#111111', fontWeight: 800 }
  if (ovr >= 68) {
    return {
      bgcolor: 'rgba(16,185,129,0.16)', color: CARBON.pitchGreen,
      border: `1px solid ${CARBON.pitchGreen}66`, fontWeight: 700,
    }
  }
  if (ovr >= 60) return { bgcolor: CARBON.bg600, color: '#E5E7EB', fontWeight: 600 }
  return {
    bgcolor: 'rgba(239,68,68,0.14)', color: CARBON.alertRed,
    border: `1px solid ${CARBON.alertRed}55`, fontWeight: 600,
  }
}
