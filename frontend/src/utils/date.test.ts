import { describe, it, expect } from 'vitest'
import { formatDatePtBR } from './date'

describe('formatDatePtBR', () => {
  it('formats an ISO date-only string as pt-BR (dd/mm/yyyy)', () => {
    expect(formatDatePtBR('2026-03-15')).toBe('15/03/2026')
  })

  it('formats an ISO datetime string, ignoring the time portion', () => {
    expect(formatDatePtBR('2026-03-15T18:30:00')).toBe('15/03/2026')
  })

  it('returns an empty string for null', () => {
    expect(formatDatePtBR(null)).toBe('')
  })

  it('returns an empty string for undefined', () => {
    expect(formatDatePtBR(undefined)).toBe('')
  })

  it('returns an empty string for an empty string', () => {
    expect(formatDatePtBR('')).toBe('')
  })

  it('does not shift the date across a UTC day boundary (timezone-safe parsing)', () => {
    // A naive `new Date(isoString)` parse would treat the date-only string as UTC
    // midnight, which can render as the previous day in timezones west of UTC.
    expect(formatDatePtBR('2026-01-01')).toBe('01/01/2026')
  })
})
