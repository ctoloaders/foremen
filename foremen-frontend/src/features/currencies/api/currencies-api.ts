import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  CurrencyDto,
  CurrencyExtendedDto,
  CurrencyCreateRequest,
  CurrencyUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the Roles / Measurement Units API
 * modules. Network calls below go through the shared {@link apiRequest} client,
 * which throws its own (structurally identical) `ApiError`; both carry `status`
 * and `message`.
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function fetchCurrencies(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<CurrencyDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<CurrencyDto>>(`${BASE_URL}/currencies?${qs}`)
}

export function fetchCurrency(id: number): Promise<CurrencyExtendedDto> {
  return apiRequest<CurrencyExtendedDto>(`${BASE_URL}/currencies/${id}`)
}

export function createCurrency(
  data: CurrencyCreateRequest,
): Promise<CurrencyExtendedDto> {
  return apiRequest<CurrencyExtendedDto>(`${BASE_URL}/currencies`, {
    method: 'POST',
    body: data,
  })
}

export function updateCurrency(
  id: number,
  data: CurrencyUpdateRequest,
): Promise<CurrencyExtendedDto> {
  return apiRequest<CurrencyExtendedDto>(`${BASE_URL}/currencies/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteCurrency(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/currencies/${id}`, { method: 'DELETE' })
}
