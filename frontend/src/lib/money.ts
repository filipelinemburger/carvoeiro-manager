export function formatBRL(value: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value)
}

/** Whole-real amount with pt-BR thousands grouping, no currency symbol: 90000 -> "90.000". */
export function formatThousandsBR(value: number): string {
  if (!Number.isFinite(value)) return ''
  return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 }).format(Math.round(value))
}

/**
 * Parse a user-typed money string into whole reais. Strips currency symbols,
 * spaces, and grouping separators so values like "90.000", "90,000" or "R$ 90.000"
 * all parse correctly. Returns NaN when no digits are present (e.g. an empty field).
 */
export function parseMoneyInput(raw: string): number {
  const digits = raw.replace(/\D/g, '')
  return digits === '' ? NaN : Number(digits)
}
