import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as client from './client'
import { loanOut, loanIn, acceptLoanCounter, getLoanOffers, withdrawLoanOffer } from './career'

describe('loan client', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('loanOut posts the player, the club and both terms', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await loanOut('S1', { playerId: 'P1', borrowerClubId: 'C2', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans/out',
      { playerId: 'P1', borrowerClubId: 'C2', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
  })

  it('loanIn posts the player and both terms', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await loanIn('S1', { playerId: 'P1', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans/in',
      { playerId: 'P1', ownerWageShare: 40, mayFaceOwner: false }, 'tok')
  })

  it('acceptLoanCounter posts to the accept-counter endpoint with no body', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await acceptLoanCounter('S1', 'OFFER1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans/OFFER1/accept-counter', {}, 'tok')
  })

  it('getLoanOffers gets the save\'s loan listing', async () => {
    const spy = vi.spyOn(client, 'apiGet').mockResolvedValue([] as never)
    await getLoanOffers('S1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans', 'tok')
  })

  it('withdrawLoanOffer posts to the withdraw endpoint with no body', async () => {
    const spy = vi.spyOn(client, 'apiPost').mockResolvedValue({} as never)
    await withdrawLoanOffer('S1', 'OFFER1', 'tok')
    expect(spy).toHaveBeenCalledWith('/save-games/S1/loans/OFFER1/withdraw', {}, 'tok')
  })
})
