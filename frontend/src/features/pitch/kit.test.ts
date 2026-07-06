import { describe, it, expect } from 'vitest'
import { readableTextColor, tokenColors, kitsClash } from './kit'

describe('readableTextColor', () => {
  it('returns white on a dark background', () => {
    expect(readableTextColor('#111111')).toBe('#ffffff')
    expect(readableTextColor('#003399')).toBe('#ffffff')
  })
  it('returns black on a light background', () => {
    expect(readableTextColor('#f7d417')).toBe('#000000')
    expect(readableTextColor('#ffffff')).toBe('#000000')
  })
})

describe('tokenColors', () => {
  it('uses the secondary colour when it contrasts with the primary', () => {
    // Criciúma yellow primary / black secondary -> readable
    expect(tokenColors({ primary: '#f7d417', secondary: '#111111' })).toEqual({ fill: '#f7d417', text: '#111111' })
  })
  it('falls back to a luminance-picked colour when secondary lacks contrast', () => {
    // near-identical primary/secondary -> secondary rejected, fall back
    expect(tokenColors({ primary: '#003399', secondary: '#0033aa' })).toEqual({ fill: '#003399', text: '#ffffff' })
  })
})

describe('kitsClash', () => {
  it('flags two near-identical primaries as a clash', () => {
    expect(kitsClash('#000000', '#000000')).toBe(true)
    expect(kitsClash('#cc0000', '#e30613')).toBe(true)
  })
  it('accepts clearly distinct primaries', () => {
    expect(kitsClash('#f7d417', '#003399')).toBe(false)
  })
})
