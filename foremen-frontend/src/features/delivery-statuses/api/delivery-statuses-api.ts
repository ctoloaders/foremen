import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  DeliveryStatusDto,
  DeliveryStatusExtendedDto,
  DeliveryStatusCreateRequest,
  DeliveryStatusUpdateRequest,
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

export function fetchDeliveryStatuses(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<DeliveryStatusDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<DeliveryStatusDto>>(`${BASE_URL}/delivery-statuses?${qs}`)
}

export function fetchDeliveryStatus(id: number): Promise<DeliveryStatusExtendedDto> {
  return apiRequest<DeliveryStatusExtendedDto>(`${BASE_URL}/delivery-statuses/${id}`)
}

export function createDeliveryStatus(
  data: DeliveryStatusCreateRequest,
): Promise<DeliveryStatusExtendedDto> {
  return apiRequest<DeliveryStatusExtendedDto>(`${BASE_URL}/delivery-statuses`, {
    method: 'POST',
    body: data,
  })
}

export function updateDeliveryStatus(
  id: number,
  data: DeliveryStatusUpdateRequest,
): Promise<DeliveryStatusExtendedDto> {
  return apiRequest<DeliveryStatusExtendedDto>(`${BASE_URL}/delivery-statuses/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteDeliveryStatus(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/delivery-statuses/${id}`, { method: 'DELETE' })
}
