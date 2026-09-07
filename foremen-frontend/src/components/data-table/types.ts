import type React from 'react'

/** Column data type — determines filter type */
export type ColumnDataType = 'string' | 'number' | 'date' | 'boolean'

/** Sort direction */
export type SortDirection = 'asc' | 'desc'

/**
 * Reference (association) descriptor mirroring the backend
 * {@code MetadataResponse.ReferenceInfo} record. Emitted for
 * `@ManyToOne`/`@OneToOne` fields and used to render a reference filter.
 */
export interface ReferenceInfo {
  /** Target resource code / API path segment (e.g. "roles") */
  targetResource: string
  /** Options list endpoint (e.g. "/api/roles") */
  optionsPath: string
  /** Display label field on the target entity (e.g. "name") */
  labelField: string
  /** Whether the label field is localized (nameRU/namePL) */
  labelI18n: boolean
  /** Id filter path composed with the query grammar (e.g. "role.id") */
  idPath: string
  /**
   * Optional constant query fragment AND-appended to the emitted id fragment
   * for this reference filter. When present, a non-empty selection emits
   * `<idFragment> AND <extraPredicate>` (e.g. the projects-list CLIENT column
   * pins the same `members` join to the CLIENT project role via
   * `members.projectRole.code==CLIENT`, reproducing the compound semantics the
   * old standalone `ProjectClientFilter` composed by hand). Omitted for plain
   * single-path reference columns, so those emit exactly as before.
   */
  extraPredicate?: string
}

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
  /**
   * Reference descriptor, present when this column maps to a
   * `@ManyToOne`/`@OneToOne` field. When set, the DataTable renders a
   * reference filter instead of the default filter for the column.
   */
  reference?: ReferenceInfo
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

/**
 * Active filter for a reference (association) column.
 *
 * The DataTable owns the per-column selected ids here so they participate in
 * the table's filter/URL/localStorage state (Req 5.1) and survive a
 * reload/restore. It is plain JSON, so it round-trips through the localStorage
 * persistence layer without special handling.
 *
 * There is no explicit single/multi mode: the selection is just a set of ids.
 * Clicking an option's name replaces the selection with that one id; clicking
 * its checkbox toggles membership. So the state carries only `ids`.
 *
 * - `ids`: selected target-entity ids. An empty array means the filter is
 *   inactive — {@link buildQueryString} emits no fragment for it.
 * - `idPath`: the reference field's id filter path (e.g. `role.id`), copied
 *   from the column's {@link ReferenceInfo} so the query builder can compose
 *   the fragment without re-reading column metadata.
 */
export interface ReferenceFilterState {
  type: 'reference'
  field: string
  ids: number[]
  idPath: string
  /**
   * Optional constant predicate copied from the column's
   * {@link ReferenceInfo.extraPredicate}. When set, {@link buildQueryString}
   * AND-appends it to the emitted id fragment so a compound nested filter
   * (e.g. `members.user.id~in~<ids> AND members.projectRole.code==CLIENT`) is
   * expressed through the standard column-filter mechanism.
   */
  extraPredicate?: string
}

/** Union type for all filters */
export type ColumnFilterState =
  | StringFilterState
  | NumberFilterState
  | DateFilterState
  | BooleanFilterState
  | ReferenceFilterState

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
  /**
   * Reference descriptor, present (non-null) for `@ManyToOne`/`@OneToOne`
   * fields; absent for scalar fields. Mirrors the backend
   * {@code FieldInfo.reference} component.
   */
  reference?: ReferenceInfo
}
