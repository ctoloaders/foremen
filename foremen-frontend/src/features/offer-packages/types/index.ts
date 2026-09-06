// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/offer-packages (paginated).
 * The `name` field is pre-resolved by the backend based on Accept-Language.
 */
export interface OfferPackageDto {
  id: number
  code: string
  orderNo: number
  name: string
  active: boolean
}

/**
 * Returned by GET /api/offer-packages/{id} (extended).
 * Contains all locale fields. Used for the edit form.
 */
export interface OfferPackageExtendedDto {
  id: number
  code: string
  orderNo: number
  nameRU: string
  namePL: string
  active: boolean
}

// --- API Request Types ---

export interface OfferPackageCreateRequest {
  code: string
  orderNo: number
  nameRU: string
  namePL: string
  active?: boolean
}

/** Update never changes `code` (code is immutable after creation). */
export interface OfferPackageUpdateRequest {
  orderNo: number
  nameRU: string
  namePL: string
  active?: boolean
}

// --- UI State Types ---

export type OfferPackageFormMode = 'create' | 'edit'
