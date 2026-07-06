export type Kit = { primary: string; secondary: string }
export type TokenColors = { fill: string; text: string }

function toRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '').slice(0, 6)
  const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

// Relative luminance (0..1), WCAG formula.
function luminance(hex: string): number {
  const [r, g, b] = toRgb(hex).map(v => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

// WCAG contrast ratio (1..21).
function contrastRatio(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}

export function readableTextColor(hexBg: string): '#000000' | '#ffffff' {
  return luminance(hexBg) > 0.5 ? '#000000' : '#ffffff'
}

export function tokenColors(kit: Kit): TokenColors {
  const usableSecondary = contrastRatio(kit.primary, kit.secondary) >= 2.5
  return { fill: kit.primary, text: usableSecondary ? kit.secondary : readableTextColor(kit.primary) }
}

// Euclidean distance in RGB; small distance == the two kits look alike on the pitch.
export function kitsClash(aPrimary: string, bPrimary: string): boolean {
  const [ar, ag, ab] = toRgb(aPrimary)
  const [br, bg, bb] = toRgb(bPrimary)
  const dist = Math.sqrt((ar - br) ** 2 + (ag - bg) ** 2 + (ab - bb) ** 2)
  return dist < 80
}
