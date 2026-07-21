export function formatDatePtBR(isoDate: string | null | undefined): string {
  if (!isoDate) return ''
  // Parse the date parts manually to avoid UTC vs local timezone offset issues.
  // ISO date-only strings (yyyy-mm-dd) would otherwise shift a day in some timezones.
  const [year, month, day] = isoDate.split('T')[0].split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString('pt-BR')
}
