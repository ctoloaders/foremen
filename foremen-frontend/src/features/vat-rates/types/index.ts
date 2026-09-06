// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/vat-rates (paginated).
 * The `name` field is pre-resolved by the backend based on Accept-Language.
 */
export interface VatRateDto {
  id: number
  code: string
  rate: number
  name: string
  isDefault: boolean
  active: boolean
}

/**
 * Returned by GET /api/vat-rates/{id} (extended).
 * Contains all locale fields. Used for the edit form.
 */
export interface VatRateExtendedDto {
  id: number
  code: string
  rate: number
  nameRU: string
  namePL: string
  isDefault: boolean
  active: boolean
}

// --- API Request Types ---

export interface VatRateCreateRequest {
  code: string
  rate: number
  nameRU: string
  namePL: string
  isDefault?: boolean
  active?: boolean
}

/** Update never changes `code` (code is immutable after creation). */
export interface VatRateUpdateRequest {
  rate: number
  nameRU: string
  namePL: string
  isDefault?: boolean
  active?: boolean
}

// --- UI State Types ---

export type VatRateFormMode = 'create' | 'edit'
