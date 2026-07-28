import type { DetailedPosition, RoleArchetype } from '../../api/types'
import { defaultArchetypeFor } from '../players/roleLabels'

export type Line = 'GK' | 'DEF' | 'MID' | 'ATT'
export type Slot = { line: Line; x: number; y: number; defaultRole: RoleArchetype; accepted: DetailedPosition[] }

type Row = { line: Line; y: number; xs: number[]; acc: DetailedPosition[][] }

// Detailed-position tags for a single slot, mirroring the backend FormationSlots (`of(code)`):
// leftmost DEF x = LATERAL_DIREITO, rightmost = LATERAL_ESQUERDO, inner = ZAGUEIRO; wide MID slots
// are MEIA_DIREITA/MEIA_ESQUERDA, central MID slots fill by depth (VOLANTE deepest, then
// MEIA_CENTRAL, then MEIA_ARMADOR most advanced); wide ATT = PONTA_DIREITA/PONTA_ESQUERDA, the
// central striker slot is ATACANTE.
const gk: DetailedPosition[] = ['GOLEIRO']
const ld: DetailedPosition[] = ['LATERAL_DIREITO']
const le: DetailedPosition[] = ['LATERAL_ESQUERDO']
const zag: DetailedPosition[] = ['ZAGUEIRO']
const vol: DetailedPosition[] = ['VOLANTE']
const mc: DetailedPosition[] = ['MEIA_CENTRAL']
const mei: DetailedPosition[] = ['MEIA_ARMADOR']
const md: DetailedPosition[] = ['MEIA_DIREITA']
const me: DetailedPosition[] = ['MEIA_ESQUERDA']
const pd: DetailedPosition[] = ['PONTA_DIREITA']
const pe: DetailedPosition[] = ['PONTA_ESQUERDA']
const st: DetailedPosition[] = ['ATACANTE']

// Each layout lists rows from own-goal (bottom, y≈0.94) up to the opposition goal (top).
// Multiple rows may share a line (e.g. a double pivot + an attacking-mid band are both MID).
// `acc[j]` carries the detailed positions the j-th slot of that row accepts, aligned with `xs`
// left→right so it matches the backend FormationSlots order slot-for-slot.
export const LAYOUTS: Record<string, Row[]> = {
  '4-4-2': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, vol, mc, me] },
    { line: 'ATT', y: 0.32, xs: [0.34, 0.66], acc: [st, st] },
  ],
  '4-3-3': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.56, xs: [0.26, 0.5, 0.74], acc: [vol, mc, mei] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8], acc: [pd, st, pe] },
  ],
  '3-5-2': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72], acc: [zag, zag, zag] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9], acc: [md, vol, mc, mei, me] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64], acc: [st, st] },
  ],
  '5-3-2': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.1, 0.3, 0.5, 0.7, 0.9], acc: [ld, zag, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.26, 0.5, 0.74], acc: [vol, mc, mei] },
    { line: 'ATT', y: 0.32, xs: [0.36, 0.64], acc: [st, st] },
  ],
  '4-5-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.1, 0.3, 0.5, 0.7, 0.9], acc: [md, vol, mc, mei, me] },
    { line: 'ATT', y: 0.3, xs: [0.5], acc: [st] },
  ],
  '4-2-3-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.62, xs: [0.36, 0.64], acc: [vol, vol] },
    { line: 'MID', y: 0.44, xs: [0.2, 0.5, 0.8], acc: [md, mei, me] },
    { line: 'ATT', y: 0.28, xs: [0.5], acc: [st] },
  ],
  '4-1-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.64, xs: [0.5], acc: [vol] },
    { line: 'MID', y: 0.48, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, mc, mei, me] },
    { line: 'ATT', y: 0.28, xs: [0.5], acc: [st] },
  ],
  '4-3-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.6, xs: [0.26, 0.5, 0.74], acc: [vol, mc, mc] },
    { line: 'MID', y: 0.44, xs: [0.36, 0.64], acc: [mei, mei] },
    { line: 'ATT', y: 0.28, xs: [0.5], acc: [st] },
  ],
  '3-4-3': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72], acc: [zag, zag, zag] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, vol, mc, me] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8], acc: [pd, st, pe] },
  ],
  // 4-4-1-1: the withdrawn "1" behind the striker mirrors the backend as an advanced playmaker
  // (MEIA_ARMADOR / MID band), keeping coarse MID = 5 / ATT = 1 in line with the code's digits.
  '4-4-1-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.56, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, vol, mc, me] },
    { line: 'MID', y: 0.4, xs: [0.5], acc: [mei] },
    { line: 'ATT', y: 0.26, xs: [0.5], acc: [st] },
  ],
  '5-4-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9], acc: [ld, zag, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, vol, mc, me] },
    { line: 'ATT', y: 0.3, xs: [0.5], acc: [st] },
  ],
  '4-2-4': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64], acc: [vol, mc] },
    { line: 'ATT', y: 0.32, xs: [0.14, 0.38, 0.62, 0.86], acc: [pd, st, st, pe] },
  ],
  '3-4-2-1': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.79, xs: [0.28, 0.5, 0.72], acc: [zag, zag, zag] },
    { line: 'MID', y: 0.58, xs: [0.16, 0.38, 0.62, 0.84], acc: [md, vol, mc, me] },
    { line: 'MID', y: 0.42, xs: [0.36, 0.64], acc: [mei, mei] },
    { line: 'ATT', y: 0.28, xs: [0.5], acc: [st] },
  ],
  '4-1-2-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.64, xs: [0.5], acc: [vol] },
    { line: 'MID', y: 0.54, xs: [0.24, 0.76], acc: [mc, mc] },
    { line: 'MID', y: 0.44, xs: [0.5], acc: [mei] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64], acc: [st, st] },
  ],
  '5-2-3': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.1, 0.3, 0.5, 0.7, 0.9], acc: [ld, zag, zag, zag, le] },
    { line: 'MID', y: 0.55, xs: [0.36, 0.64], acc: [vol, mc] },
    { line: 'ATT', y: 0.32, xs: [0.2, 0.5, 0.8], acc: [pd, st, pe] },
  ],
  '4-3-1-2': [
    { line: 'GK', y: 0.94, xs: [0.5], acc: [gk] },
    { line: 'DEF', y: 0.8, xs: [0.16, 0.38, 0.62, 0.84], acc: [ld, zag, zag, le] },
    { line: 'MID', y: 0.58, xs: [0.26, 0.5, 0.74], acc: [vol, mc, mc] },
    { line: 'MID', y: 0.44, xs: [0.5], acc: [mei] },
    { line: 'ATT', y: 0.3, xs: [0.36, 0.64], acc: [st, st] },
  ],
}

export const FORMATION_CODES = Object.keys(LAYOUTS).sort((a, b) => a.localeCompare(b, 'pt-BR', { numeric: true }))

export function pitchSlots(formation: string): Slot[] {
  const rows = LAYOUTS[formation] ?? LAYOUTS['4-4-2']
  return rows.flatMap(row =>
    row.xs.map((x, j) => ({
      line: row.line,
      x,
      y: row.y,
      defaultRole: defaultArchetypeFor(row.acc[j][0]),
      accepted: row.acc[j],
    })),
  )
}
