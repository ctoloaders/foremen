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

function getAcceptLanguage(): string {
  try {
    const locale = localStorage.getItem('foremen-locale')
    if (locale === 'ru') return 'ru'
  } catch {}
  return 'pl'
}

function getHeaders(extra?: Record<string, string>): Record<string, string> {
  return {
    'Accept-Language': getAcceptLanguage(),
    ...extra,
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

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
}): Promise<PaginatedResponse<RoleDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  return fetch(`${BASE_URL}/roles?${searchParams}`, { headers: getHeaders() }).then((r) =>
    handleResponse<PaginatedResponse<RoleDto>>(r),
  )
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
  return fetch(`${BASE_URL}/roles/extended?${searchParams}`, { headers: getHeaders() }).then((r) =>
    handleResponse<PaginatedResponse<RoleExtendedDto>>(r),
  )
}

export function fetchRole(id: number): Promise<RoleExtendedDto> {
  return fetch(`${BASE_URL}/roles/${id}`, { headers: getHeaders() }).then((r) =>
    handleResponse<RoleExtendedDto>(r),
  )
}

export function createRole(data: RoleCreateRequest): Promise<RoleExtendedDto> {
  return fetch(`${BASE_URL}/roles`, {
    method: 'POST',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  }).then((r) => handleResponse<RoleExtendedDto>(r))
}

export function updateRole(id: number, data: RoleUpdateRequest): Promise<RoleExtendedDto> {
  return fetch(`${BASE_URL}/roles/${id}`, {
    method: 'PUT',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  }).then((r) => handleResponse<RoleExtendedDto>(r))
}

export function deleteRole(id: number): Promise<void> {
  return fetch(`${BASE_URL}/roles/${id}`, { method: 'DELETE', headers: getHeaders() }).then((r) => {
    if (!r.ok) return handleResponse<void>(r)
  })
}

export function fetchResources(params: {
  page?: number
  size?: number
}): Promise<PaginatedResponse<ResourceDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  return fetch(`${BASE_URL}/resources?${searchParams}`, { headers: getHeaders() }).then((r) =>
    handleResponse<PaginatedResponse<ResourceDto>>(r),
  )
}

export function fetchOperations(params: {
  page?: number
  size?: number
}): Promise<PaginatedResponse<OperationDto>> {
  const searchParams = new URLSearchParams()
  if (params.page != null) searchParams.set('page', String(params.page))
  if (params.size != null) searchParams.set('size', String(params.size))
  return fetch(`${BASE_URL}/operations?${searchParams}`, { headers: getHeaders() }).then((r) =>
    handleResponse<PaginatedResponse<OperationDto>>(r),
  )
}

export function fetchRolePermissions(roleId: number): Promise<RolePermissionResponse> {
  return fetch(`${BASE_URL}/roles/${roleId}/permissions`, { headers: getHeaders() }).then((r) =>
    handleResponse<RolePermissionResponse>(r),
  )
}

export function batchUpdatePermissions(
  data: BatchRolePermissionRequest,
): Promise<BatchRolePermissionResponse> {
  return fetch(`${BASE_URL}/roles/permissions/batch`, {
    method: 'PUT',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  }).then((r) => handleResponse<BatchRolePermissionResponse>(r))
}
