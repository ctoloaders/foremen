import { apiRequest } from '@/lib/api-client'
import type { PlacePredictionDto, PlaceDetailsDto } from '../types'

const BASE_URL = '/api'

/**
 * Standalone, reusable Google Places proxy client (FOR-04-13 Requirements 5.1, 5.2). Calls the
 * backend `/api/addresses/*` endpoints, which inject the server-side API key so it never reaches
 * the browser. This module is deliberately NOT project-specific — address capture is cross-cutting,
 * so future features (e.g. procurement) can import it directly.
 */

/**
 * Fetches Google Places autocomplete predictions for a free-text address `query`
 * (`GET /api/addresses/autocomplete`). Each prediction carries a `description` and a `placeId`.
 */
export function autocompleteAddress(query: string): Promise<PlacePredictionDto[]> {
  const qs = new URLSearchParams({ query }).toString()
  return apiRequest<PlacePredictionDto[]>(`${BASE_URL}/addresses/autocomplete?${qs}`)
}

/**
 * Resolves canonical address details for a Google `placeId`
 * (`GET /api/addresses/details`): `formattedAddress`, `latitude`, `longitude`, and minimal
 * components. Used after a prediction is selected to capture the values persisted on a project.
 */
export function fetchAddressDetails(placeId: string): Promise<PlaceDetailsDto> {
  const qs = new URLSearchParams({ placeId }).toString()
  return apiRequest<PlaceDetailsDto>(`${BASE_URL}/addresses/details?${qs}`)
}
