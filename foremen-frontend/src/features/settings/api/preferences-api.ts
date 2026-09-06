import { apiRequest } from '@/lib/api-client'
import type { DisplayPreferencesRequest, DisplayPreferencesResponse } from '../types'

/**
 * Fetch display preferences for a user (GET /api/users/{id}/display-preferences).
 *
 * Routed through the shared Api_Client so the request carries the Authorization
 * bearer token (plus Accept-Language and 401 refresh/retry). The previous bespoke
 * fetch wrapper sent no Authorization header, so the backend treated the caller
 * as unauthenticated and returned 403.
 */
export function fetchDisplayPreferences(
  userId: number,
): Promise<DisplayPreferencesResponse> {
  return apiRequest<DisplayPreferencesResponse>(`/api/users/${userId}/display-preferences`)
}

/**
 * Patch (update) display preferences for a user
 * (PATCH /api/users/{id}/display-preferences).
 */
export function patchDisplayPreferences(
  userId: number,
  body: DisplayPreferencesRequest,
): Promise<DisplayPreferencesResponse> {
  return apiRequest<DisplayPreferencesResponse>(
    `/api/users/${userId}/display-preferences`,
    { method: 'PATCH', body },
  )
}
