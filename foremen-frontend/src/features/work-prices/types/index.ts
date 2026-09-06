// --- API Response Types ---

export interface PaginatedResponse<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number // current page (0-indexed)
  size: number
}

/**
 * Returned by GET /api/work-prices (paginated).
 *
 * The referenced display values (`workItemName`, `currencyCode`) are resolved
 * server-side (workItemName is locale-aware: RU for `ru`, PL otherwise) so the
 * table renders them directly. `current` is derived server-side (`validTo == null`).
 */
export interface WorkPriceDto {
  id: number
  workItemId: number
  workItemName: string
  currencyId: number
  currencyCode: string
  netPrice: number
  validFrom: string
  validTo: string | null
  current: boolean
}

/**
 * Returned by GET /api/work-prices/{id} (extended).
 * Contains the flat FK ids + price + dates. Used for the edit form.
 */
export interface WorkPriceExtendedDto {
  id: number
  workItemId: number
  currencyId: number
  netPrice: number
  validFrom: string
  validTo: string | null
}

// --- API Request Types ---

export interface WorkPriceCreateRequest {
  workItemId: number
  currencyId: number
  netPrice: number
  validFrom: string
  validTo?: string | null
}

export interface WorkPriceUpdateRequest {
  workItemId: number
  currencyId: number
  netPrice: number
  validFrom: string
  validTo?: string | null
}

// --- UI State Types ---

export type WorkPriceFormMode = 'create' | 'edit'
