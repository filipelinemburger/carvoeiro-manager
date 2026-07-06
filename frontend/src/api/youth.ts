import { apiGet, apiPost } from './client'
import type { YouthProspectResponse } from './types'

export function listProspects(saveGameId: string, token: string): Promise<YouthProspectResponse[]> {
  return apiGet<YouthProspectResponse[]>(`/save-games/${saveGameId}/youth-prospects`, token)
}

export function promoteProspect(
  saveGameId: string,
  prospectId: string,
  token: string,
): Promise<YouthProspectResponse> {
  return apiPost<YouthProspectResponse>(
    `/save-games/${saveGameId}/youth-prospects/${prospectId}/promote`,
    {},
    token,
  )
}

// The backend release endpoint is POST and returns 204 No Content.
export function releaseProspect(saveGameId: string, prospectId: string, token: string): Promise<void> {
  return apiPost<void>(`/save-games/${saveGameId}/youth-prospects/${prospectId}/release`, {}, token)
}
