import { describe, it, expect } from 'vitest'
import { formatThousandsBR, parseMoneyInput } from './money'

describe('formatThousandsBR', () => {
  it('groups whole reais with pt-BR separators', () => {
    expect(formatThousandsBR(90000)).toBe('90.000')
    expect(formatThousandsBR(91287)).toBe('91.287')
    expect(formatThousandsBR(0)).toBe('0')
  })

  it('rounds fractional amounts to whole reais', () => {
    expect(formatThousandsBR(68466.4)).toBe('68.466')
    expect(formatThousandsBR(68466.6)).toBe('68.467')
  })

  it('returns an empty string for non-finite input', () => {
    expect(formatThousandsBR(NaN)).toBe('')
  })
})

describe('parseMoneyInput', () => {
  it('parses grouped pt-BR input', () => {
    expect(parseMoneyInput('90.000')).toBe(90000)
  })

  it('parses values with stray US-style separators without becoming NaN', () => {
    // This was the bug: Number("90,000.00") is NaN, which serialized to a null wage.
    expect(parseMoneyInput('90,000.00')).toBe(9000000)
    expect(parseMoneyInput('90,000')).toBe(90000)
  })

  it('strips currency symbols and spaces', () => {
    expect(parseMoneyInput('R$ 91.287')).toBe(91287)
  })

  it('returns NaN for an empty or symbol-only field', () => {
    expect(parseMoneyInput('')).toBeNaN()
    expect(parseMoneyInput('R$ ')).toBeNaN()
  })
})
