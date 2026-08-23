// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/** Returned by GET /api/roles (paginated). Pre-resolved locale fields. */
export interface RoleDto {
  id: number
  code: string
  name: string
  description: string | null
  system: boolean
}

/**
 * Returned by GET /api/roles/extended (paginated).
 * Contains all locale fields + system flag.
 * Used for the edit form.
 */
export interface RoleExtendedDto {
  id: number
  code: string
  nameRU: string
  namePL: string
  descriptionRU: string | null
  descriptionPL: string | null
  system: boolean
}

/**
 * Returned by GET /api/resources/extended (paginated).
 * Contains all locale fields for the detail/edit view.
 */
export interface ResourceExtendedDto {
  id: number
  code: string
  nameRU: string
  namePL: string
  descriptionRU: string | null
  descriptionPL: string | null
}

/**
 * Returned by GET /api/resources (paginated).
 * The `name` and `description` fields are pre-resolved by the backend
 * based on Accept-Language header. Used in the permission matrix.
 */
export interface ResourceDto {
  id: number
  code: string
  name: string
  description: string | null
}

/**
 * Returned by GET /api/operations (paginated).
 * The `name` field is pre-resolved by the backend.
 * Used in the permission matrix.
 */
export interface OperationDto {
  id: number
  code: string
  name: string
}

export interface PermissionEntry {
  resourceId: number
  resourceCode: string
  resourceName: string
  operations: OperationInfo[]
}

export interface OperationInfo {
  operationId: number
  operationCode: string
  operationName: string
}

export interface RolePermissionResponse {
  roleId: number
  permissions: PermissionEntry[]
}

// --- Batch Permission Types ---

export interface BatchRolePermissionEntry {
  roleId: number
  permissions: PermissionEntryRequest[]
}

export interface BatchRolePermissionRequest {
  entries: BatchRolePermissionEntry[]
}

export interface BatchRolePermissionResponse {
  results: RolePermissionResponse[]
}

// --- API Request Types ---

export interface RoleCreateRequest {
  code: string
  nameRU: string
  namePL: string
  descriptionRU?: string
  descriptionPL?: string
}

export interface RoleUpdateRequest {
  nameRU: string
  namePL: string
  descriptionRU?: string
  descriptionPL?: string
}

export interface PermissionEntryRequest {
  resourceId: number
  operationIds: number[]
}

export interface RolePermissionRequest {
  permissions: PermissionEntryRequest[]
}

// --- UI State Types ---

export type RoleFormMode = 'create' | 'edit'

export interface MatrixCellState {
  /** Map of operationId → active (true/false) */
  [operationId: number]: boolean
}

/** roleId → resourceId → MatrixCellState */
export type MatrixLocalState = Map<number, Map<number, MatrixCellState>>

/** Set of roleIds that have been modified */
export type DirtyRoleIds = Set<number>
