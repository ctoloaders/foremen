import type { DisplayPreferencesRequest, DisplayPreferencesResponse } from '../types'

const BASE_URL = '/api'

function getAcceptLanguage(): string {
  try {
    const locale = localStorage.getItem('foremen-locale')
    if (locale === 'ru') return 'ru'
  } catch {}
  return 'pl'
}

function getHeaders(extra?: Record<string, string>): Record<string, string> {
  return {
    'Accept-Language': getAcceptLanguage(),
    ...extra,
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const message = body.message || body.error || `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  return response.json()
}

/**
 * Fetch display preferences for a user.
 * GET /api/users/{id}/display-preferences
 */
export function fetchDisplayPreferences(userId: number): Promise<DisplayPreferencesResponse> {
  return fetch(`${BASE_URL}/users/${userId}/display-preferences`, {
    headers: getHeaders(),
  }).then((r) => handleResponse<DisplayPreferencesResponse>(r))
}

/**
 * Patch (update) display preferences for a user.
 * PATCH /api/users/{id}/display-preferences
 */
export function patchDisplayPreferences(
  userId: number,
  body: DisplayPreferencesRequest,
): Promise<DisplayPreferencesResponse> {
  return fetch(`${BASE_URL}/users/${userId}/display-preferences`, {
    method: 'PATCH',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  }).then((r) => handleResponse<DisplayPreferencesResponse>(r))
}
