import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  VatRateDto,
  VatRateExtendedDto,
  VatRateCreateRequest,
  VatRateUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the Currencies / Measurement Units API
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

export function fetchVatRates(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<VatRateDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<VatRateDto>>(`${BASE_URL}/vat-rates?${qs}`)
}

export function fetchVatRate(id: number): Promise<VatRateExtendedDto> {
  return apiRequest<VatRateExtendedDto>(`${BASE_URL}/vat-rates/${id}`)
}

export function createVatRate(
  data: VatRateCreateRequest,
): Promise<VatRateExtendedDto> {
  return apiRequest<VatRateExtendedDto>(`${BASE_URL}/vat-rates`, {
    method: 'POST',
    body: data,
  })
}

export function updateVatRate(
  id: number,
  data: VatRateUpdateRequest,
): Promise<VatRateExtendedDto> {
  return apiRequest<VatRateExtendedDto>(`${BASE_URL}/vat-rates/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteVatRate(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/vat-rates/${id}`, { method: 'DELETE' })
}
