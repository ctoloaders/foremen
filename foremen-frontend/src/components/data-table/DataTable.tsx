import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { SlidersHorizontal, ScrollText } from 'lucide-react'

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
import { AuditModal } from './AuditModal'

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

  // Audit modal state
  const [auditRow, setAuditRow] = useState<{ entityId: number } | null>(null)

  const handleClearFilters = useCallback(() => {
    dispatch({ type: 'CLEAR_ALL' })
  }, [dispatch])

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
  }, [state.filters, dispatch])

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
    <div className="space-y-4">
      {/* Toolbar: search + filter count + clear all */}
      <DataTableToolbar state={state} dispatch={dispatch} />

      {/* Mobile: "Filters" button to toggle collapsible filter toolbar */}
      {isMobile && (
        <Button
          variant="outline"
          size="sm"
          className="w-full"
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
      )}

      {/* Mobile collapsible filter controls */}
      {isMobile && mobileFiltersOpen && (
        <div className="space-y-2 rounded-lg border bg-card p-4">
          {columns
            .filter(col => col.filterable !== false)
            .map(col => (
              <div key={col.field} className="space-y-1">
                <Button
                  variant="ghost"
                  size="sm"
                  className="w-full justify-start"
                  onClick={() => {
                    // On mobile, set filter directly via dispatch (mobile UX)
                  }}
                >
                  {t(col.headerKey)}
                  {state.filters.find(f => f.field === col.field) && (
                    <span className="ml-auto text-primary text-xs">
                      {t('dataTable.filter.active', { defaultValue: 'Active' })}
                    </span>
                  )}
                </Button>
              </div>
            ))}
        </div>
      )}

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

      {/* Pagination */}
      {!query.isLoading && data.length > 0 && (
        <DataTablePagination
          state={state}
          dispatch={dispatch}
          totalElements={totalElements}
          totalPages={totalPages}
          isFirst={isFirst}
          isLast={isLast}
          pageSizeOptions={pageSizeOptions}
        />
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
