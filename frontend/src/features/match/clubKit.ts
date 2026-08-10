import { tokenColors, type Kit } from '../pitch/kit'
import type { LiveKitColors } from '../../api/types'

const FALLBACK: Kit = { primary: '#5b6670', secondary: '#ffffff' }
const HEX = /^#?[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$/

// `LiveKitColors` is typed non-nullable, but the backend's Club.primaryColor/secondaryColor
// are nullable columns, so the JSON can carry null (or an empty/non-hex string) despite the
// type saying string. Falls back per-channel rather than per-kit so a valid secondary survives
// a missing primary and vice versa.
function safeColor(value: string | null | undefined, fallback: string): string {
  return value && HEX.test(value) ? value : fallback
}

/** Guards a possibly-malformed kit before any colour maths sees it — never pass raw
 * `LiveKitColors` straight into `tokenColors`, or a null/non-hex value turns into
 * `parseInt(undefined, 16)` -> `NaN` -> an unreadable `rgb(NaN, NaN, NaN)` card. */
export function safeKit(colors: LiveKitColors | null | undefined): Kit {
  return {
    primary: safeColor(colors?.primary, FALLBACK.primary),
    secondary: safeColor(colors?.secondary, FALLBACK.secondary),
  }
}

/** The club card's background/text colours. Reuses `tokenColors` (features/pitch/kit.ts)
 * verbatim for the actual colour maths — this only supplies a guaranteed-safe `Kit`. */
export function cardColors(colors: LiveKitColors | null | undefined): { bg: string; fg: string } {
  const { fill, text } = tokenColors(safeKit(colors))
  return { bg: fill, fg: text }
}
