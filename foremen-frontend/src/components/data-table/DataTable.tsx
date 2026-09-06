import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import {
  SlidersHorizontal,
  ScrollText,
  ChevronDown,
  X,
  ArrowUp,
  ArrowDown,
  ArrowUpDown,
} from 'lucide-react'

import { useBreakpoint } from '@/hooks/useBreakpoint'
import { usePermission } from '@/hooks/usePermission'
import { Table } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
  TooltipProvider,
} from '@/components/ui/tooltip'

import type { DataTableProps, ColumnDataType } from './types'
import { useDataTable } from './hooks/useDataTable'
import { DataTableToolbar } from './DataTableToolbar'
import { DataTableHeader } from './DataTableHeader'
import { DataTableBody } from './DataTableBody'
import { DataTableCards } from './DataTableCards'
import { DataTablePagination } from './DataTablePagination'
import { DataTableSkeleton } from './DataTableSkeleton'
import { DataTableEmpty } from './DataTableEmpty'
import { StringFilter } from './filters/StringFilter'
import { NumberFilter } from './filters/NumberFilter'
import { DateFilter } from './filters/DateFilter'
import { BooleanFilter } from './filters/BooleanFilter'
import { ReferenceFilter } from './ReferenceFilter'
import { AuditModal } from './AuditModal'
import { countActiveFilters } from './utils/countActiveFilters'

export function DataTable<T>(props: DataTableProps<T>) {
  const { columns, onRowClick, rowActions, pageSizeOptions, showAuditButton, entityKey, resource } =
    props
  const { t } = useTranslation()
  const { hasPermission } = usePermission()
  const breakpoint = useBreakpoint()
  const isMobile = breakpoint === 'mobile'
  const isTablet = breakpoint === 'tablet'

  const { state, dispatch, query } = useDataTable(props)

  // Mobile filter drawer toggle
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(false)

  // Per-column disclosure inside the mobile filters panel: which column's
  // filter control is currently expanded (null = all collapsed). Each column
  // renders its header as a disclosure button that reveals the SAME control the
  // desktop header uses (via `renderFilter`), so inputs/dropdowns/checkboxes/
  // the reference filter and their apply/clear are all interactive (Req 7.2).
  const [expandedMobileFilter, setExpandedMobileFilter] = useState<string | null>(null)

  // Audit modal state
  const [auditRow, setAuditRow] = useState<{ entityId: number } | null>(null)

  const handleClearFilters = useCallback(() => {
    dispatch({ type: 'CLEAR_ALL' })
  }, [dispatch])

  // Apply → collapse (Req 7.6): closing the mobile filters panel also collapses
  // the currently-open per-column disclosure so that reopening starts from a
  // clean, all-collapsed state and the applied summary takes its place. The
  // individual filter controls dispatch SET_FILTER on their own Apply button,
  // so this panel-level Apply is purely a "Done — close the panel" affordance.
  const handleApplyFilters = useCallback(() => {
    setMobileFiltersOpen(false)
    setExpandedMobileFilter(null)
  }, [])

  // "Clear all" from the collapsed summary state (Req 7.7): reset every column
  // filter + sort (CLEAR_ALL) and let the query refetch (its queryKey depends on
  // filters/sorts). Also collapse the panel back to its closed state.
  const handleClearAllFromSummary = useCallback(() => {
    dispatch({ type: 'CLEAR_ALL' })
    setMobileFiltersOpen(false)
    setExpandedMobileFilter(null)
  }, [dispatch])

  // Applied-summary counts (Req 7.6): a reference filter with ≥1 selected id
  // counts as one filter; every other filter entry counts as one; sorts count
  // is simply the number of active sort clauses.
  const filtersCount = countActiveFilters(state.filters)
  const sortsCount = state.sorts.length
  const hasAppliedSummary = filtersCount > 0 || sortsCount > 0

  // Localized applied-summary text with PL/RU plural handling. Two independently
  // pluralized fragments (filters / sorts) joined by a localized separator, so
  // each count gets the correct CLDR plural category for the active locale.
  const summaryText = [
    t('dataTable.filters.summaryFilters', { count: filtersCount }),
    t('dataTable.filters.summarySorts', { count: sortsCount }),
  ].join(t('dataTable.filters.summarySeparator'))

  // The audit button is visible only when it is enabled AND the table declares
  // a `resource` AND the current user may read the audit trail (FOR-03-07,
  // Req 6.5). Audit uses the fixed `AUDIT` resource regardless of the table's
  // own `resource`.
  const auditVisible =
    showAuditButton !== false && resource != null && hasPermission('AUDIT', 'READ')

  // Build combined row actions (user actions + audit button)
  const combinedRowActions = useCallback((row: T) => {
    const rowId = (row as Record<string, unknown>).id as number

    const auditButton = auditVisible ? (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              onClick={(e) => {
                e.stopPropagation()
                setAuditRow({ entityId: rowId })
              }}
              aria-label={t('audit.button.viewAudit')}
            >
              <ScrollText className="h-4 w-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t('audit.button.viewAudit')}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    ) : null

    const userActions = rowActions ? rowActions(row) : null

    if (!auditButton && !userActions) return null

    return (
      <div className="flex items-center gap-1">
        {userActions}
        {auditButton}
      </div>
    )
  }, [auditVisible, rowActions, t])

  // Render filter content for a given field — passed to DataTableHeader
  const renderFilter = useCallback((field: string, dataType: ColumnDataType) => {
    const currentFilter = state.filters.find(f => f.field === field)

    // Reference columns take precedence over the default filter controls: when
    // the column carries a `reference` descriptor, render <ReferenceFilter>
    // instead of the string/number/date/boolean control (Req 5.3). The
    // DataTable owns the selected ids + mode via the existing filters array (a
    // ReferenceFilterState), so they participate in filter/URL/localStorage
    // state and survive reload (Req 5.1).
    const column = columns.find(c => c.field === field)
    if (column?.reference) {
      const referenceFilter =
        currentFilter?.type === 'reference' ? currentFilter : undefined
      const ids = referenceFilter?.ids ?? []
      const idPath = column.reference.idPath

      const commit = (nextIds: number[]) => {
        // Empty selection → clear the filter entirely so it contributes no
        // fragment and stops counting as an active filter; otherwise upsert the
        // reference filter state.
        if (nextIds.length === 0) {
          dispatch({ type: 'CLEAR_FILTER', payload: { field } })
        } else {
          dispatch({
            type: 'SET_FILTER',
            payload: { type: 'reference', field, ids: nextIds, idPath },
          })
        }
      }

      return (
        <ReferenceFilter
          reference={column.reference}
          value={ids}
          onChange={commit}
        />
      )
    }

    switch (dataType) {
      case 'string':
        return (
          <StringFilter
            field={field}
            currentValue={currentFilter?.type === 'string' ? currentFilter.value : undefined}
            onApply={(value) => {
              dispatch({ type: 'SET_FILTER', payload: { type: 'string', field, value } })
            }}
            onClose={() => {}}
          />
        )
      case 'number':
        return (
          <NumberFilter
            field={field}
            currentFrom={currentFilter?.type === 'number' ? currentFilter.from : undefined}
            currentTo={currentFilter?.type === 'number' ? currentFilter.to : undefined}
            onApply={(from, to) => {
              dispatch({ type: 'SET_FILTER', payload: { type: 'number', field, from, to } })
            }}
            onClose={() => {}}
          />
        )
      case 'date':
        return (
          <DateFilter
            field={field}
            currentFrom={currentFilter?.type === 'date' ? currentFilter.from : undefined}
            currentTo={currentFilter?.type === 'date' ? currentFilter.to : undefined}
            onApply={(from, to) => {
              dispatch({ type: 'SET_FILTER', payload: { type: 'date', field, from, to } })
            }}
            onClose={() => {}}
          />
        )
      case 'boolean':
        return (
          <BooleanFilter
            field={field}
            currentValue={currentFilter?.type === 'boolean' ? currentFilter.value : undefined}
            onApply={(value) => {
              dispatch({ type: 'SET_FILTER', payload: { type: 'boolean', field, value } })
            }}
            onClose={() => {}}
          />
        )
    }
  }, [state.filters, dispatch, columns])

  // Determine data and pagination info from query response
  const data = query.data?.content ?? []
  const totalElements = query.data?.totalElements ?? 0
  const totalPages = query.data?.totalPages ?? 0
  const isFirst = query.data?.first ?? true
  const isLast = query.data?.last ?? true

  // Check if we have any row actions to render. The actions column (and its
  // header) collapses entirely when nothing is visible: it renders only when
  // the audit button is visible OR the page's `rowActions(row)` yields a
  // non-null/non-empty result for at least one row (FOR-03-07, Req 6.6, 8.3).
  const hasVisibleUserActions =
    !!rowActions &&
    data.some((row) => {
      const node = rowActions(row)
      if (node == null || node === false) return false
      if (Array.isArray(node)) return node.length > 0
      return true
    })
  const hasRowActions = auditVisible || hasVisibleUserActions

  return (
    // Three-region flex column (Req 7.5, 7.8): a bounded-height flex column
    // whose header (search + filters toggle) and footer (pagination) sit
    // OUTSIDE the scroll container and stay pinned, while only the middle body
    // region scrolls. `min-h-0` lets the flex body shrink so `overflow-y-auto`
    // engages; the `max-h` keeps the column bounded even when the page slot
    // provides no explicit height (natural document flow) so the layout never
    // collapses to zero height and desktop is not regressed.
    <div className="flex flex-col min-h-0 max-h-[calc(100vh-12rem)]">
      {/* Header region — pinned top, never scrolls (Req 7.5) */}
      <div className="flex-shrink-0 space-y-4">
        {/* Toolbar: search + filter count + clear all */}
        <DataTableToolbar state={state} dispatch={dispatch} />

        {/* Mobile: "Filters" toggle + applied summary.
            When the panel is collapsed AND at least one filter/sort is active,
            an applied-summary chip renders next to the toggle (Req 7.6): the
            chip itself is a control that reopens the panel (Req 7.7), and a
            trailing "clear all" control resets filters+sorts from the collapsed
            state (Req 7.7). While the panel is open the summary is hidden so it
            does not compete with the live controls. */}
        {isMobile && (
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              className="flex-1"
              onClick={() => setMobileFiltersOpen(!mobileFiltersOpen)}
            >
              <SlidersHorizontal className="mr-2 h-4 w-4" />
              {t('dataTable.filters.toggle', { defaultValue: 'Filters' })}
              {state.filters.length > 0 && (
                <span className="ml-2 rounded-full bg-primary text-primary-foreground text-xs px-1.5 py-0.5">
                  {state.filters.length}
                </span>
              )}
            </Button>

            {!mobileFiltersOpen && hasAppliedSummary && (
              <div className="flex flex-1 items-center gap-1 rounded-full border bg-muted/50 pl-3 pr-1 py-1 text-xs">
                <button
                  type="button"
                  className="flex-1 truncate text-left font-medium"
                  aria-label={t('dataTable.filters.summaryLabel')}
                  onClick={() => setMobileFiltersOpen(true)}
                >
                  {summaryText}
                </button>
                <button
                  type="button"
                  className="rounded-full p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
                  aria-label={t('dataTable.filters.clearAll')}
                  onClick={handleClearAllFromSummary}
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            )}
          </div>
        )}

        {/* Mobile collapsible filter controls.
            Each filterable column is a disclosure: the button toggles the
            column open, and when open the ACTUAL filter control (the same one
            the desktop header renders via `renderFilter`) is rendered inline in
            normal flow. This replaces the old dead column-name buttons whose
            onClick did nothing — inputs, selects, checkboxes, the reference
            dropdown and each control's apply/clear are now fully clickable and
            dispatch SET_FILTER through the shared `renderFilter` wiring (Req
            7.2, 7.9). The panel container sits in normal flow with no
            pointer-events:none and no covering overlay/stacking context. */}
        {isMobile && mobileFiltersOpen && (
          <div className="space-y-2 rounded-lg border bg-card p-4">
            {/* Sort section (Req 7.3): an explicit per-column ascending/
                descending control for the mobile panel, mirroring the desktop
                header's sortable affordance. Each sortable column gets a cycle
                button that dispatches TOGGLE_SORT (unsorted → asc → desc →
                unsorted); the active direction is shown via ArrowUp/ArrowDown
                and, for multi-sort, a priority badge. This reuses the same
                TOGGLE_SORT action + buildSortParams mapping as desktop, so the
                `sort=field,dir` append semantics and the applied-summary
                `sortsCount` are unchanged. */}
            {columns.some(col => col.sortable !== false) && (
              <div className="rounded-md border bg-background">
                <div className="flex items-center gap-2 px-3 py-2 text-sm font-medium text-muted-foreground">
                  <ArrowUpDown className="h-4 w-4 shrink-0" />
                  <span>{t('dataTable.sort.label')}</span>
                </div>
                <div className="border-t px-3 py-2 space-y-1">
                  {columns
                    .filter(col => col.sortable !== false)
                    .map(col => {
                      const sort = state.sorts.find(s => s.field === col.field)
                      const directionLabel = sort
                        ? sort.direction === 'asc'
                          ? t('dataTable.sort.asc')
                          : t('dataTable.sort.desc')
                        : t('dataTable.sort.none')
                      return (
                        <button
                          key={col.field}
                          type="button"
                          className="flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-sm hover:bg-muted"
                          aria-label={t('dataTable.sort.toggle')}
                          onClick={() =>
                            dispatch({
                              type: 'TOGGLE_SORT',
                              payload: { field: col.field },
                            })
                          }
                        >
                          <span className="flex-1 truncate">{t(col.headerKey)}</span>
                          <span
                            className={
                              sort
                                ? 'text-foreground text-xs'
                                : 'text-muted-foreground text-xs'
                            }
                          >
                            {directionLabel}
                          </span>
                          {sort ? (
                            sort.direction === 'asc' ? (
                              <ArrowUp className="h-4 w-4 shrink-0 text-foreground" />
                            ) : (
                              <ArrowDown className="h-4 w-4 shrink-0 text-foreground" />
                            )
                          ) : (
                            <ArrowUpDown className="h-4 w-4 shrink-0 text-muted-foreground/50" />
                          )}
                          {sort && state.sorts.length > 1 && (
                            <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] text-primary-foreground">
                              {sort.priority}
                            </span>
                          )}
                        </button>
                      )
                    })}
                </div>
              </div>
            )}

            {columns
              .filter(col => col.filterable !== false)
              .map(col => {
                const isExpanded = expandedMobileFilter === col.field
                const hasActiveFilter = state.filters.some(f => f.field === col.field)
                return (
                  <div key={col.field} className="rounded-md border bg-background">
                    <button
                      type="button"
                      className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium"
                      aria-expanded={isExpanded}
                      onClick={() =>
                        setExpandedMobileFilter(isExpanded ? null : col.field)
                      }
                    >
                      <span>{t(col.headerKey)}</span>
                      {hasActiveFilter && (
                        <span className="text-primary text-xs">
                          {t('dataTable.filter.active', { defaultValue: 'Active' })}
                        </span>
                      )}
                      <ChevronDown
                        className={`ml-auto h-4 w-4 shrink-0 text-muted-foreground transition-transform ${
                          isExpanded ? 'rotate-180' : ''
                        }`}
                      />
                    </button>
                    {isExpanded && (
                      <div className="border-t px-3 py-3">
                        {renderFilter(col.field, col.dataType)}
                      </div>
                    )}
                  </div>
                )
              })}

            {/* Panel-level Apply / Done: individual controls already dispatch
                SET_FILTER on their own Apply, so this closes the panel and
                surfaces the applied summary (Req 7.6). */}
            <Button
              variant="default"
              size="sm"
              className="w-full"
              onClick={handleApplyFilters}
            >
              {t('dataTable.filters.apply', { defaultValue: 'Apply' })}
            </Button>
          </div>
        )}
      </div>

      {/* Body region — the ONLY scroll container (flex-1, min-h-0,
          overflow-y-auto). Rows/cards/skeleton/empty state scroll here while
          header and footer stay pinned (Req 7.5). */}
      <div className="flex-1 min-h-0 overflow-y-auto py-4">
      {/* Main content */}
      {query.isLoading ? (
        isMobile ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="h-24 rounded-lg bg-muted animate-pulse" />
            ))}
          </div>
        ) : (
          <div className={isTablet ? 'overflow-x-auto' : ''}>
            <Table>
              <DataTableHeader
                columns={columns}
                state={state}
                dispatch={dispatch}
                renderFilter={renderFilter}
              />
              <DataTableSkeleton columnCount={columns.length} />
            </Table>
          </div>
        )
      ) : query.isError ? (
        <DataTableEmpty type="error" onRetry={() => query.refetch()} />
      ) : data.length === 0 ? (
        <DataTableEmpty type="empty" onClearFilters={handleClearFilters} />
      ) : isMobile ? (
        <div className={query.isFetching ? 'opacity-60 transition-opacity' : ''}>
          <DataTableCards
            data={data}
            columns={columns}
            onRowClick={onRowClick}
            rowActions={hasRowActions ? combinedRowActions : undefined}
          />
        </div>
      ) : (
        <div
          className={`${isTablet ? 'overflow-x-auto' : ''} ${query.isFetching ? 'opacity-60 transition-opacity' : ''}`}
        >
          <Table>
            <DataTableHeader
              columns={columns}
              state={state}
              dispatch={dispatch}
              renderFilter={renderFilter}
            />
            <DataTableBody
              data={data}
              columns={columns}
              onRowClick={onRowClick}
              rowActions={hasRowActions ? combinedRowActions : undefined}
            />
          </Table>
        </div>
      )}
      </div>

      {/* Footer region — pinned bottom, never scrolls (Req 7.5) */}
      {!query.isLoading && data.length > 0 && (
        <div className="flex-shrink-0">
          <DataTablePagination
            state={state}
            dispatch={dispatch}
            totalElements={totalElements}
            totalPages={totalPages}
            isFirst={isFirst}
            isLast={isLast}
            pageSizeOptions={pageSizeOptions}
          />
        </div>
      )}

      {/* Audit Modal */}
      {auditRow && (
        <AuditModal
          open={true}
          onClose={() => setAuditRow(null)}
          entityKey={entityKey}
          entityId={auditRow.entityId}
        />
      )}
    </div>
  )
}
