import { apiRequest } from '@/lib/api-client'
import { buildFetchQuery } from '@/components/data-table'
import type {
  PaginatedResponse,
  MaterialCategoryDto,
  MaterialCategoryExtendedDto,
  MaterialCategoryCreateRequest,
  MaterialCategoryUpdateRequest,
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

export function fetchMaterialCategories(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<MaterialCategoryDto>> {
  // Serialize through the shared DataTable serializer so page/size/query/sort
  // are sent consistently with every other managed-entity table.
  const qs = buildFetchQuery({
    page: params.page ?? 0,
    size: params.size ?? 10,
    query: params.query,
    sort: params.sort ?? [],
  })
  return apiRequest<PaginatedResponse<MaterialCategoryDto>>(`${BASE_URL}/material-categories?${qs}`)
}

export function fetchMaterialCategory(id: number): Promise<MaterialCategoryExtendedDto> {
  return apiRequest<MaterialCategoryExtendedDto>(`${BASE_URL}/material-categories/${id}`)
}

export function createMaterialCategory(
  data: MaterialCategoryCreateRequest,
): Promise<MaterialCategoryExtendedDto> {
  return apiRequest<MaterialCategoryExtendedDto>(`${BASE_URL}/material-categories`, {
    method: 'POST',
    body: data,
  })
}

export function updateMaterialCategory(
  id: number,
  data: MaterialCategoryUpdateRequest,
): Promise<MaterialCategoryExtendedDto> {
  return apiRequest<MaterialCategoryExtendedDto>(`${BASE_URL}/material-categories/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteMaterialCategory(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/material-categories/${id}`, { method: 'DELETE' })
}
