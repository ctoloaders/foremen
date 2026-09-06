import { apiRequest } from '@/lib/api-client'
import type {
  PaginatedResponse,
  RoleDto,
  RoleExtendedDto,
  ResourceDto,
  OperationDto,
  RoleCreateRequest,
  RoleUpdateRequest,
  RolePermissionResponse,
  BatchRolePermissionRequest,
  BatchRolePermissionResponse,
} from '../types'

const BASE_URL = '/api'

/**
 * Local error type kept for backward compatibility with callers that import
 * `ApiError` from this module. Network calls below go through the shared
 * {@link apiRequest} client, which throws its own (structurally identical)
 * `ApiError`; both carry `status` and `message`.
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

/**
 * Standalone response handler retained for the unit tests and any direct
 * callers. New network functions delegate to {@link apiRequest} instead, which
 * attaches the `Authorization: Bearer` header and performs the `401`
 * refresh/retry + forced-logout redirect.
 */
export async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const message = body.message || body.error || `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  return response.json()
}

export function fetchRoles(params: {
  page?: number
  size?: number
  query?: string
  sort?: string[]
}): Promise<PaginatedResponse<RoleDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  for (const sortEntry of params.sort ?? []) {
    searchParams.append('sort', sortEntry)
  }
  const qs = searchParams.toString().replace(/%7E/gi, '~')
  return apiRequest<PaginatedResponse<RoleDto>>(`${BASE_URL}/roles?${qs}`)
}

export function fetchRolesExtended(params: {
  page?: number
  size?: number
  query?: string
}): Promise<PaginatedResponse<RoleExtendedDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  return apiRequest<PaginatedResponse<RoleExtendedDto>>(
    `${BASE_URL}/roles/extended?${searchParams}`,
  )
}

export function fetchRole(id: number): Promise<RoleExtendedDto> {
  return apiRequest<RoleExtendedDto>(`${BASE_URL}/roles/${id}`)
}

export function createRole(data: RoleCreateRequest): Promise<RoleExtendedDto> {
  return apiRequest<RoleExtendedDto>(`${BASE_URL}/roles`, {
    method: 'POST',
    body: data,
  })
}

export function updateRole(id: number, data: RoleUpdateRequest): Promise<RoleExtendedDto> {
  return apiRequest<RoleExtendedDto>(`${BASE_URL}/roles/${id}`, {
    method: 'PUT',
    body: data,
  })
}

export async function deleteRole(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/roles/${id}`, { method: 'DELETE' })
}

export function fetchResources(params: {
  page?: number
  size?: number
}): Promise<PaginatedResponse<ResourceDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  return apiRequest<PaginatedResponse<ResourceDto>>(`${BASE_URL}/resources?${searchParams}`)
}

export function fetchOperations(params: {
  page?: number
  size?: number
}): Promise<PaginatedResponse<OperationDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  return apiRequest<PaginatedResponse<OperationDto>>(`${BASE_URL}/operations?${searchParams}`)
}

export function fetchRolePermissions(roleId: number): Promise<RolePermissionResponse> {
  return apiRequest<RolePermissionResponse>(`${BASE_URL}/roles/${roleId}/permissions`)
}

export function batchUpdatePermissions(
  data: BatchRolePermissionRequest,
): Promise<BatchRolePermissionResponse> {
  return apiRequest<BatchRolePermissionResponse>(`${BASE_URL}/roles/permissions/batch`, {
    method: 'PUT',
    body: data,
  })
}
