import { describe, it, expect } from 'vitest'
import { safeKit, cardColors } from './clubKit'
import type { LiveKitColors } from '../../api/types'

const FALLBACK = { primary: '#5b6670', secondary: '#ffffff' }

describe('safeKit', () => {
  it('passes through valid 6-digit hex colours unchanged', () => {
    expect(safeKit({ primary: '#003399', secondary: '#ffffff' })).toEqual({
      primary: '#003399', secondary: '#ffffff',
    })
  })

  it('passes through valid 3-digit hex colours unchanged', () => {
    expect(safeKit({ primary: '#f00', secondary: 'abc' })).toEqual({
      primary: '#f00', secondary: 'abc',
    })
  })

  it('falls back to the whole default kit when colours is null or undefined', () => {
    expect(safeKit(null)).toEqual(FALLBACK)
    expect(safeKit(undefined)).toEqual(FALLBACK)
  })

  // The real defect this guards: LiveKitColors is typed non-nullable, but the backend's
  // Club.primaryColor/secondaryColor are nullable columns, so the JSON can carry null despite
  // the type saying string.
  it('falls back per-channel when a colour is null', () => {
    const colors = { primary: null, secondary: '#003399' } as unknown as LiveKitColors
    expect(safeKit(colors)).toEqual({ primary: FALLBACK.primary, secondary: '#003399' })
  })

  it('falls back per-channel when a colour is undefined', () => {
    const colors = { primary: undefined, secondary: '#003399' } as unknown as LiveKitColors
    expect(safeKit(colors)).toEqual({ primary: FALLBACK.primary, secondary: '#003399' })
  })

  it('falls back per-channel when a colour is empty', () => {
    expect(safeKit({ primary: '', secondary: '#003399' })).toEqual({
      primary: FALLBACK.primary, secondary: '#003399',
    })
  })

  it('falls back per-channel when a colour is not hex-shaped', () => {
    expect(safeKit({ primary: 'red', secondary: '#12' })).toEqual({
      primary: FALLBACK.primary, secondary: FALLBACK.secondary,
    })
  })
})

describe('cardColors', () => {
  it('reuses tokenColors: a light primary yields dark text', () => {
    // Same primary/secondary -> no usable contrast -> falls back to a luminance-picked colour.
    expect(cardColors({ primary: '#f7d417', secondary: '#f7d417' })).toEqual({
      bg: '#f7d417', fg: '#000000',
    })
  })

  it('reuses tokenColors: a dark primary yields light text', () => {
    expect(cardColors({ primary: '#111111', secondary: '#111111' })).toEqual({
      bg: '#111111', fg: '#ffffff',
    })
  })

  it('falls back to the default kit with no NaN when primary is null', () => {
    const colors = { primary: null, secondary: '#003399' } as unknown as LiveKitColors
    const result = cardColors(colors)
    expect(JSON.stringify(result)).not.toContain('NaN')
    expect(result).toEqual({ bg: FALLBACK.primary, fg: '#ffffff' })
  })

  it('falls back to the default kit with no NaN when colours is null', () => {
    const result = cardColors(null)
    expect(JSON.stringify(result)).not.toContain('NaN')
    expect(result).toEqual({ bg: FALLBACK.primary, fg: FALLBACK.secondary })
  })
})
