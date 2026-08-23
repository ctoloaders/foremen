// --- API Response Types ---

/** Returned by GET /api/users (paginated list view). Pre-resolved locale fields. */
export interface UserDto {
  id: number
  name: string
  email: string
  active: boolean
  roleName: string // locale-resolved by backend
}

/** Returned by GET /api/users/{id} (detail for edit form) */
export interface UserExtendedDto {
  id: number
  name: string
  email: string
  phone: string | null
  roleId: number
  roleName: string
  active: boolean
  locale: string
  displayPreferences: Record<string, unknown> | null
}

// --- API Request Types ---

/** POST /api/users request body */
export interface UserCreateRequest {
  name: string
  email: string
  phone?: string | null
  roleId: number
  locale: string
  displayPreferences?: Record<string, unknown>
}

/** PUT /api/users/{id} request body */
export interface UserUpdateRequest {
  name: string
  email: string
  phone?: string | null
  roleId: number
  active: boolean
  locale: string
  displayPreferences?: Record<string, unknown>
}

// --- UI Types ---

/** Role item for the select dropdown (from GET /api/roles) */
export interface RoleOption {
  id: number
  name: string // locale-resolved
}

/** Re-export PaginatedResponse from data-table types */
export type { PaginatedResponse, FetchParams } from '@/components/data-table'

/** Form mode type */
export type UserFormMode = 'create' | 'edit'
