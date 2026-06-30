import { describe, it } from 'vitest'

// AdvancePanel was removed. The "Avançar" action now lives in the Layout shell.
// Coverage is in Layout.test.tsx and AdvanceTimeline.test.tsx.
describe('AdvancePanel (removed)', () => {
  it('is superseded by the Layout shell Avançar action — see Layout.test.tsx', () => {
    // No-op: this test suite exists only to keep the file from being an empty module
  })
})
