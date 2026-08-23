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

/**
 * Adapter for DataTable's FetchParams — fetches paginated users list.
 * GET /api/users with page, size, sort[], and query params.
 */
export async function fetchUsers(params: FetchParams): Promise<PaginatedResponse<UserDto>> {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page))
  searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  for (const sortEntry of params.sort) {
    searchParams.append('sort', sortEntry)
  }

  const response = await fetch(`${BASE_URL}/users?${searchParams}`, {
    headers: getHeaders(),
  })
  const data = await handleResponse<PaginatedResponse<UserDto>>(response)

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
  return fetch(`${BASE_URL}/users/${id}`, { headers: getHeaders() }).then((r) =>
    handleResponse<UserExtendedDto>(r),
  )
}

/**
 * Create a new user.
 * POST /api/users
 */
export function createUser(data: UserCreateRequest): Promise<UserExtendedDto> {
  return fetch(`${BASE_URL}/users`, {
    method: 'POST',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  }).then((r) => handleResponse<UserExtendedDto>(r))
}

/**
 * Update an existing user.
 * PUT /api/users/{id}
 */
export function updateUser(id: number, data: UserUpdateRequest): Promise<UserExtendedDto> {
  return fetch(`${BASE_URL}/users/${id}`, {
    method: 'PUT',
    headers: getHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(data),
  }).then((r) => handleResponse<UserExtendedDto>(r))
}

/**
 * Deactivate (soft-delete) a user.
 * DELETE /api/users/{id}
 */
export function deactivateUser(id: number): Promise<void> {
  return fetch(`${BASE_URL}/users/${id}`, { method: 'DELETE', headers: getHeaders() }).then((r) => {
    if (!r.ok) return handleResponse<void>(r)
  })
}

/**
 * Fetch all roles for the select dropdown.
 * Fetches all pages from GET /api/roles to build a complete list.
 */
export async function fetchRolesForSelect(): Promise<RoleOption[]> {
  const roles: RoleOption[] = []
  let page = 0
  let totalPages = 1

  while (page < totalPages) {
    const searchParams = new URLSearchParams()
    searchParams.set('page', String(page))
    searchParams.set('size', '100')

    const response = await fetch(`${BASE_URL}/roles?${searchParams}`, {
      headers: getHeaders(),
    })
    const data = await handleResponse<PaginatedResponse<{ id: number; name: string }>>(response)

    roles.push(...data.content.map((r) => ({ id: r.id, name: r.name })))
    totalPages = data.totalPages
    page++
  }

  return roles
}
