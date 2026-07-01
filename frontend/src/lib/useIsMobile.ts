import { useMediaQuery, useTheme } from '@mui/material'

/**
 * True on compact viewports (below the `md` breakpoint), where data tables are
 * stacked into cards and the header nav collapses into a Drawer.
 *
 * In jsdom `window.matchMedia` is undefined, so MUI's useMediaQuery returns
 * `false` — tests render the desktop layout and existing table-based queries
 * keep matching. No test-time mock is required.
 */
export function useIsMobile(): boolean {
  const theme = useTheme()
  return useMediaQuery(theme.breakpoints.down('md'))
}
