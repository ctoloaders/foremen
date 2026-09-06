import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  DeliveryCategoryDto,
  DeliveryCategoryExtendedDto,
  DeliveryCategoryCreateRequest,
  DeliveryCategoryUpdateRequest,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for parity with the Roles API module. Network calls
 * below go through the shared {@link apiRequest} client, which throws its own
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

export function fetchDeliveryCategories(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<DeliveryCategoryDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<DeliveryCategoryDto>>(`${BASE_URL}/delivery-categories?${qs}`)
}

export function fetchDeliveryCategory(id: number): Promise<DeliveryCategoryExtendedDto> {
  return apiRequest<DeliveryCategoryExtendedDto>(`${BASE_URL}/delivery-categories/${id}`)
}

export function createDeliveryCategory(
  data: DeliveryCategoryCreateRequest,
): Promise<DeliveryCategoryExtendedDto> {
  return apiRequest<DeliveryCategoryExtendedDto>(`${BASE_URL}/delivery-categories`, {
    method: 'POST',
    body: data,
  })
}

export function updateDeliveryCategory(
  id: number,
  data: DeliveryCategoryUpdateRequest,
): Promise<DeliveryCategoryExtendedDto> {
  return apiRequest<DeliveryCategoryExtendedDto>(`${BASE_URL}/delivery-categories/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteDeliveryCategory(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/delivery-categories/${id}`, { method: 'DELETE' })
}
