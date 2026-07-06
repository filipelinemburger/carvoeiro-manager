import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import { apiGet, apiPost } from './client'
import { listProspects, promoteProspect, releaseProspect } from './youth'

const mockGet = vi.mocked(apiGet)
const mockPost = vi.mocked(apiPost)

describe('youth api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists prospects under the save-scoped path', () => {
    listProspects('sg-1', 'tok')
    expect(mockGet).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects', 'tok')
  })

  it('promotes via POST to the nested path', () => {
    promoteProspect('sg-1', 'p-1', 'tok')
    expect(mockPost).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects/p-1/promote', {}, 'tok')
  })

  it('releases via POST to the nested path', () => {
    releaseProspect('sg-1', 'p-1', 'tok')
    expect(mockPost).toHaveBeenCalledWith('/save-games/sg-1/youth-prospects/p-1/release', {}, 'tok')
  })
})
