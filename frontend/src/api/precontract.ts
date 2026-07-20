import { apiDelete, apiGet, apiPost } from './client'
import type { PreContractAgreement, PreContractEligible, PreContractOfferResult } from './types'

export function getPreContractEligible(saveGameId: string, token: string): Promise<PreContractEligible[]> {
  return apiGet<PreContractEligible[]>(`/save-games/${saveGameId}/pre-contracts/eligible`, token)
}

export function makePreContractOffer(
  saveGameId: string,
  req: { playerId: string; wage: number; contractYears: number },
  token: string,
): Promise<PreContractOfferResult> {
  return apiPost<PreContractOfferResult>(`/save-games/${saveGameId}/pre-contracts/offers`, req, token)
}

export function acceptPreContractCounter(
  saveGameId: string,
  playerId: string,
  req: { contractYears: number },
  token: string,
): Promise<PreContractOfferResult> {
  return apiPost<PreContractOfferResult>(
    `/save-games/${saveGameId}/pre-contracts/offers/${playerId}/accept-counter`, req, token)
}

export function getPreContractAgreements(saveGameId: string, token: string): Promise<PreContractAgreement[]> {
  return apiGet<PreContractAgreement[]>(`/save-games/${saveGameId}/pre-contracts/agreements`, token)
}

export function withdrawPreContract(saveGameId: string, agreementId: string, token: string): Promise<void> {
  return apiDelete<void>(`/save-games/${saveGameId}/pre-contracts/agreements/${agreementId}`, token)
}
