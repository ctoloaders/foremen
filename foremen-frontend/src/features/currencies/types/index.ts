// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/currencies (paginated).
 * The `name` field is pre-resolved by the backend based on Accept-Language.
 */
export interface CurrencyDto {
  id: number
  code: string
  symbol: string
  name: string
  active: boolean
}

/**
 * Returned by GET /api/currencies/{id} (extended).
 * Contains all locale fields. Used for the edit form.
 */
export interface CurrencyExtendedDto {
  id: number
  code: string
  symbol: string
  nameRU: string
  namePL: string
  active: boolean
}

// --- API Request Types ---

export interface CurrencyCreateRequest {
  code: string
  symbol: string
  nameRU: string
  namePL: string
  active?: boolean
}

/** Update never changes `code` (code is immutable after creation). */
export interface CurrencyUpdateRequest {
  symbol: string
  nameRU: string
  namePL: string
  active?: boolean
}

// --- UI State Types ---

export type CurrencyFormMode = 'create' | 'edit'
