import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  WorkCategoryDto,
  WorkCategoryExtendedDto,
  WorkCategoryCreateRequest,
  WorkCategoryUpdateRequest,
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

export function fetchWorkCategories(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<WorkCategoryDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<WorkCategoryDto>>(`${BASE_URL}/work-categories?${qs}`)
}

export function fetchWorkCategory(id: number): Promise<WorkCategoryExtendedDto> {
  return apiRequest<WorkCategoryExtendedDto>(`${BASE_URL}/work-categories/${id}`)
}

export function createWorkCategory(
  data: WorkCategoryCreateRequest,
): Promise<WorkCategoryExtendedDto> {
  return apiRequest<WorkCategoryExtendedDto>(`${BASE_URL}/work-categories`, {
    method: 'POST',
    body: data,
  })
}

export function updateWorkCategory(
  id: number,
  data: WorkCategoryUpdateRequest,
): Promise<WorkCategoryExtendedDto> {
  return apiRequest<WorkCategoryExtendedDto>(`${BASE_URL}/work-categories/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteWorkCategory(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/work-categories/${id}`, { method: 'DELETE' })
}
