import type { PlayerRole } from '../../api/types'

export type Line = 'GK' | 'DEF' | 'MID' | 'ATT'
export type Slot = { line: Line; x: number; y: number; defaultRole: PlayerRole }

type Row = { line: Line; y: number; xs: number[] }

const DEFAULT_ROLE: Record<Line, PlayerRole> = {
  GK: 'GOALKEEPER', DEF: 'FULL_BACK', MID: 'BOX_TO_BOX', ATT: 'POACHER',
}

// Each layout lists rows from own-goal (bottom, y≈0.94) up to the opposition goal (top).
// Multiple rows may share a line (e.g. a double pivot + an attacking-mid band are both MID).
const LAYOUTS: Record<string, Row[]> = {
  '4-4-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.32, xs: [0.34, 0.66] },
  ],
  '4-3-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.56, xs: [0.26, 0.5, 0.74] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '3-5-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64] },
  ],
  '5-3-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.26, 0.5, 0.74] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64] },
  ],
  '4-5-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'ATT', y: 0.3, xs: [0.5] },
  ],
  '4-2-3-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.62, xs: [0.36, 0.64] },
    { line: 'MID', y: 0.44, xs: [0.2, 0.5, 0.8] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-1-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.64, xs: [0.5] },
    { line: 'MID', y: 0.48, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-3-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.6, xs: [0.26, 0.5, 0.74] },
    { line: 'MID', y: 0.44, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '3-4-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '4-4-1-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.56, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.4, xs: [0.5] },
    { line: 'ATT', y: 0.26, xs: [0.5] },
  ],
  '5-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'ATT', y: 0.3, xs: [0.5] },
  ],
  '4-2-4': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.32, xs: [0.14, 0.38, 0.62, 0.86] },
  ],
  '3-4-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72] },
    { line: 'MID', y: 0.58, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.42, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.28, xs: [0.5] },
  ],
  '4-1-2-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.64, xs: [0.5] },
    { line: 'MID', y: 0.54, xs: [0.24, 0.76] },
    { line: 'MID', y: 0.44, xs: [0.5] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64] },
  ],
  '5-2-3': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8] },
  ],
  '4-3-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84] },
    { line: 'MID', y: 0.58, xs: [0.26, 0.5, 0.74] },
    { line: 'MID', y: 0.44, xs: [0.5] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64] },
  ],
}

export const FORMATION_CODES = Object.keys(LAYOUTS)

export function pitchSlots(formation: string): Slot[] {
  const rows = LAYOUTS[formation] ?? LAYOUTS['4-4-2']
  return rows.flatMap(row =>
    row.xs.map(x => ({ line: row.line, x, y: row.y, defaultRole: DEFAULT_ROLE[row.line] })),
  )
}
