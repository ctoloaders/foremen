import { apiRequest } from '@/lib/api-client'
import type {
  FetchParams,
  PaginatedResponse,
  UserDto,
  UserExtendedDto,
  UserCreateRequest,
  UserUpdateRequest,
  RoleOption,
} from '../types'

const BASE_URL = '/api'

/**
 * Re-exported from the shared Api_Client so existing imports
 * (`import { ApiError } from '../api/users-api'`) keep working. The shared
 * error carries the HTTP status and the backend's verbatim message.
 */
export { ApiError } from '@/lib/api-client'

/**
 * Adapter for DataTable's FetchParams — fetches paginated users list.
 * GET /api/users with page, size, sort[], and query params.
 *
 * All requests go through the shared {@link apiRequest} client, which attaches
 * the `Authorization: Bearer <token>` header from the Auth_Store and handles a
 * `401` via single-flight refresh/retry and forced logout + redirect.
 */
export async function fetchUsers(params: FetchParams): Promise<PaginatedResponse<UserDto>> {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page))
  searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  for (const sortEntry of params.sort) {
    searchParams.append('sort', sortEntry)
  }

  const data = await apiRequest<PaginatedResponse<UserDto>>(`${BASE_URL}/users?${searchParams}`)

  return {
    ...data,
    first: data.number === 0,
    last: data.number >= data.totalPages - 1,
  }
}

/**
 * Fetch a single user by ID (for edit form).
 * GET /api/users/{id}
 */
export function fetchUser(id: number): Promise<UserExtendedDto> {
  return apiRequest<UserExtendedDto>(`${BASE_URL}/users/${id}`)
}

/**
 * Create a new user.
 * POST /api/users
 */
export function createUser(data: UserCreateRequest): Promise<UserExtendedDto> {
  return apiRequest<UserExtendedDto>(`${BASE_URL}/users`, {
    method: 'POST',
    body: data,
  })
}

/**
 * Update an existing user.
 * PUT /api/users/{id}
 */
export function updateUser(id: number, data: UserUpdateRequest): Promise<UserExtendedDto> {
  return apiRequest<UserExtendedDto>(`${BASE_URL}/users/${id}`, {
    method: 'PUT',
    body: data,
  })
}

/**
 * Deactivate (soft-delete) a user.
 * DELETE /api/users/{id}
 */
export async function deactivateUser(id: number): Promise<void> {
  await apiRequest<void>(`${BASE_URL}/users/${id}`, { method: 'DELETE' })
}

/**
 * Fetch a single page of roles for the select dropdown.
 * GET /api/roles with page, size, and optional RSQL-style query params
 * (e.g. query=name~ct~{search}).
 *
 * Returns the raw Spring Data page so callers (e.g. useInfiniteQuery) can
 * paginate via `last` / `number` and lazily load subsequent pages.
 */
export function fetchRolesPage(params: {
  page: number
  size: number
  query?: string
}): Promise<PaginatedResponse<RoleOption>> {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page))
  searchParams.set('size', String(params.size))

  // Build the URL manually so the RSQL query keeps literal `~` operators
  // (e.g. `name~ct~{search}`). `URLSearchParams` would percent-encode `~` to
  // `%7E`; `~` is an RFC 3986 unreserved character so a literal tilde is valid
  // and keeps the wire format aligned with the documented `query=name~ct~...`.
  let url = `${BASE_URL}/roles?${searchParams}`
  if (params.query) {
    url += `&query=${encodeURIComponent(params.query).replace(/%7E/gi, '~')}`
  }

  return apiRequest<PaginatedResponse<RoleOption>>(url)
}

/**
 * Fetch all roles for the select dropdown.
 * Fetches all pages from GET /api/roles to build a complete list.
 *
 * @deprecated Use {@link fetchRolesPage} with an infinite query instead. This
 * loads every page eagerly and does not scale; kept temporarily until the
 * RoleSelect component migrates to paginated/infinite fetching.
 */
export async function fetchRolesForSelect(): Promise<RoleOption[]> {
  const roles: RoleOption[] = []
  let page = 0
  let totalPages = 1

  while (page < totalPages) {
    const data = await fetchRolesPage({ page, size: 100 })

    roles.push(...data.content.map((r) => ({ id: r.id, name: r.name, code: r.code })))
    totalPages = data.totalPages
    page++
  }

  return roles
}
