// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/work-items (paginated).
 *
 * The `name` field is pre-resolved by the backend based on Accept-Language. The
 * referenced display names (`workCategoryName`, `unitName`) are likewise resolved
 * server-side (RU for `ru`, PL otherwise) so the table renders them directly.
 */
export interface WorkItemDto {
  id: number
  workCategoryId: number
  workCategoryName: string
  unitId: number
  unitName: string
  name: string
  active: boolean
}

/**
 * Returned by GET /api/work-items/{id} (extended).
 * Contains the flat FK ids + all locale fields. Used for the edit form.
 */
export interface WorkItemExtendedDto {
  id: number
  workCategoryId: number
  unitId: number
  nameRU: string
  namePL: string
  active: boolean
}

// --- API Request Types ---

export interface WorkItemCreateRequest {
  workCategoryId: number
  unitId: number
  nameRU: string
  namePL: string
  active?: boolean
}

/** Update accepts the FKs (both mutable — there is no immutable `code` here). */
export interface WorkItemUpdateRequest {
  workCategoryId: number
  unitId: number
  nameRU: string
  namePL: string
  active?: boolean
}

// --- UI State Types ---

export type WorkItemFormMode = 'create' | 'edit'
