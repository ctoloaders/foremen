// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/measurement-units (paginated).
 * The `name` field is pre-resolved by the backend based on Accept-Language.
 */
export interface MeasurementUnitDto {
  id: number
  code: string
  name: string
  active: boolean
}

/**
 * Returned by GET /api/measurement-units/{id} (extended).
 * Contains all locale fields. Used for the edit form.
 */
export interface MeasurementUnitExtendedDto {
  id: number
  code: string
  nameRU: string
  namePL: string
  active: boolean
}

// --- API Request Types ---

export interface MeasurementUnitCreateRequest {
  code: string
  nameRU: string
  namePL: string
  active?: boolean
}

/** Update never changes `code` (code is immutable after creation). */
export interface MeasurementUnitUpdateRequest {
  nameRU: string
  namePL: string
  active?: boolean
}

// --- UI State Types ---

export type MeasurementUnitFormMode = 'create' | 'edit'
