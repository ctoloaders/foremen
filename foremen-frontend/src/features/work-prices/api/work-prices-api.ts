import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  WorkPriceDto,
  WorkPriceExtendedDto,
  WorkPriceCreateRequest,
  WorkPriceUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the other dictionary API modules. Network
 * calls below go through the shared {@link apiRequest} client, which throws its own
 * (structurally identical) `ApiError`; both carry `status` and `message`.
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

export function fetchWorkPrices(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<WorkPriceDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<WorkPriceDto>>(`${BASE_URL}/work-prices?${qs}`)
}

export function fetchWorkPrice(id: number): Promise<WorkPriceExtendedDto> {
  return apiRequest<WorkPriceExtendedDto>(`${BASE_URL}/work-prices/${id}`)
}

export function createWorkPrice(data: WorkPriceCreateRequest): Promise<WorkPriceExtendedDto> {
  return apiRequest<WorkPriceExtendedDto>(`${BASE_URL}/work-prices`, {
    method: 'POST',
    body: data,
  })
}

export function updateWorkPrice(
  id: number,
  data: WorkPriceUpdateRequest,
): Promise<WorkPriceExtendedDto> {
  return apiRequest<WorkPriceExtendedDto>(`${BASE_URL}/work-prices/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteWorkPrice(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/work-prices/${id}`, { method: 'DELETE' })
}
