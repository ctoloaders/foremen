// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/room-types (paginated).
 * The `name` field is pre-resolved by the backend based on Accept-Language.
 */
export interface RoomTypeDto {
  id: number
  code: string
  name: string
  active: boolean
}

/**
 * Returned by GET /api/room-types/{id} (extended).
 * Contains all locale fields. Used for the edit form.
 */
export interface RoomTypeExtendedDto {
  id: number
  code: string
  nameRU: string
  namePL: string
  active: boolean
}

// --- API Request Types ---

export interface RoomTypeCreateRequest {
  code: string
  nameRU: string
  namePL: string
  active?: boolean
}

/** Update never changes `code` (code is immutable after creation). */
export interface RoomTypeUpdateRequest {
  nameRU: string
  namePL: string
  active?: boolean
}

// --- UI State Types ---

export type RoomTypeFormMode = 'create' | 'edit'
