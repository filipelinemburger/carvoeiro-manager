import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as client from './client'
import { getFreeAgents, signFreeAgent, renewContract, listPlayer, unlistPlayer } from './career'

describe('contract client', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('getFreeAgents calls the free-agents endpoint', async () => {
    const spy = vi.spyOn(client, 'apiGet').mockResolvedValue([] as never)
    await getFreeAgents('S1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/free-agents', 'tok')
  })

  it('signFreeAgent posts wage + length', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await signFreeAgent('S1', 'P1', { wage: 100, contractYears: 3 }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/free-agents/P1/sign', { wage: 100, contractYears: 3 }, 'tok')
  })

  it('renewContract posts to the renew endpoint', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await renewContract('S1', 'P1', { wage: 200, contractYears: 2 }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/players/P1/renew', { wage: 200, contractYears: 2 }, 'tok')
  })

  it('listPlayer puts to the listings endpoint', async () => {
    const spy = vi.spyOn(client, 'apiPut').mockResolvedValue({} as never)
    await listPlayer('S1', 'P1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/transfers/listings/P1', {}, 'tok')
  })

  it('unlistPlayer deletes the listings endpoint', async () => {
    const spy = vi.spyOn(client, 'apiDelete').mockResolvedValue({} as never)
    await unlistPlayer('S1', 'P1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/transfers/listings/P1', 'tok')
  })
})
