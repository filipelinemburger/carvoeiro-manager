import { createTheme } from '@mui/material/styles'

/**
 * Material UI theme carrying the Criciúma EC brand palette (yellow / black / green),
 * so MUI components render on-brand. Intentionally no CssBaseline at the root — the
 * app's stadium body background and white content panel live in theme.css.
 */
export const muiTheme = createTheme({
  palette: {
    primary: { main: '#f7d417', contrastText: '#111111' },
    secondary: { main: '#1f7a3f', contrastText: '#ffffff' },
    success: { main: '#1f7a3f' },
    text: { primary: '#111111', secondary: '#555555' },
  },
  typography: { fontFamily: 'system-ui, sans-serif' },
  shape: { borderRadius: 8 },
  components: {
    MuiButton: {
      defaultProps: { variant: 'contained', disableElevation: true },
      styleOverrides: { root: { fontWeight: 700, textTransform: 'none' } },
    },
  },
})
