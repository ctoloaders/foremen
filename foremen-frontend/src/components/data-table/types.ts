import type React from 'react'

/** Column data type — determines filter type */
export type ColumnDataType = 'string' | 'number' | 'date' | 'boolean'

/** Sort direction */
export type SortDirection = 'asc' | 'desc'

/** Single column configuration */
export interface ColumnConfig<T = unknown> {
  /** Field identifier (dot-notation for nested, e.g. "role.name") */
  field: string
  /** i18n key for column header */
  headerKey: string
  /** Data type for filter */
  dataType: ColumnDataType
  /** Column is sortable (default: true) */
  sortable?: boolean
  /** Column is filterable (default: true for string/number/date) */
  filterable?: boolean
  /** Column participates in global search (default: true for string) */
  searchable?: boolean
  /** Custom render function */
  render?: (value: unknown, row: T) => React.ReactNode
  /** Minimum column width (CSS) */
  minWidth?: string
}

/** Active sort state */
export interface SortState {
  field: string
  direction: SortDirection
  priority: number
}

/** Active filter (string) */
export interface StringFilterState {
  type: 'string'
  field: string
  value: string
}

/** Active filter (number) */
export interface NumberFilterState {
  type: 'number'
  field: string
  from?: number
  to?: number
}

/** Active filter (date) */
export interface DateFilterState {
  type: 'date'
  field: string
  from?: string // ISO date string
  to?: string // ISO date string
}

/** Active filter (boolean) */
export interface BooleanFilterState {
  type: 'boolean'
  field: string
  value: true | false | null  // null means "is null / not assigned"
}

/** Union type for all filters */
export type ColumnFilterState =
  | StringFilterState
  | NumberFilterState
  | DateFilterState
  | BooleanFilterState

/** Full table state */
export interface TableState {
  page: number
  size: number
  sorts: SortState[]
  filters: ColumnFilterState[]
  search: string
}

/** Props for DataTable */
export interface DataTableProps<T> {
  /** Unique entity key (for localStorage and metadata) */
  entityKey: string
  /** Column configuration */
  columns: ColumnConfig<T>[]
  /** Data fetch function */
  fetchFn: (params: FetchParams) => Promise<PaginatedResponse<T>>
  /** Default page size (default: 25) */
  defaultPageSize?: number
  /** Configurable page size options (default: [10, 25, 50]) */
  pageSizeOptions?: number[]
  /** Callback on row click */
  onRowClick?: (row: T) => void
  /** Additional action buttons per row */
  rowActions?: (row: T) => React.ReactNode
  /** Show audit button in row actions (default: true) */
  showAuditButton?: boolean
  /**
   * ABAC resource code this table represents (e.g. "USERS"). When set, the
   * composed audit button is gated behind `hasPermission('AUDIT', 'READ')`
   * (FOR-03-07). Left unset for tables that opt out of permission gating.
   */
  resource?: string
  /** Default sort configuration applied when no persisted state exists */
  defaultSort?: SortState[]
}

/** API request parameters */
export interface FetchParams {
  page: number
  size: number
  sort: string[] // ["field,direction", ...]
  query?: string // Query DSL string
}

/** Backend response (Spring Data Page<T>) */
export interface PaginatedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number // current page (0-based)
  size: number
  first: boolean
  last: boolean
}

/** Metadata endpoint response */
export interface EntityMetadata {
  fields: FieldMetadata[]
}

export interface FieldMetadata {
  name: string
  dataType: 'STRING' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'ENUM'
  i18n: boolean
  nested?: FieldMetadata[]
}
