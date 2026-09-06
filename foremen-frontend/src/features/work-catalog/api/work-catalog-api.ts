import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  WorkItemDto,
  WorkItemExtendedDto,
  WorkItemCreateRequest,
  WorkItemUpdateRequest,
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

export function fetchWorkItems(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<WorkItemDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<WorkItemDto>>(`${BASE_URL}/work-items?${qs}`)
}

export function fetchWorkItem(id: number): Promise<WorkItemExtendedDto> {
  return apiRequest<WorkItemExtendedDto>(`${BASE_URL}/work-items/${id}`)
}

export function createWorkItem(data: WorkItemCreateRequest): Promise<WorkItemExtendedDto> {
  return apiRequest<WorkItemExtendedDto>(`${BASE_URL}/work-items`, {
    method: 'POST',
    body: data,
  })
}

export function updateWorkItem(
  id: number,
  data: WorkItemUpdateRequest,
): Promise<WorkItemExtendedDto> {
  return apiRequest<WorkItemExtendedDto>(`${BASE_URL}/work-items/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteWorkItem(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/work-items/${id}`, { method: 'DELETE' })
}
