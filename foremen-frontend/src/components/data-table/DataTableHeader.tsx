import { type Dispatch, type ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowUp, ArrowDown, Filter, X } from 'lucide-react'

import { TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'

import type { ColumnConfig, ColumnDataType, TableState } from './types'
import type { TableAction } from './hooks/useTableState'

interface DataTableHeaderProps<T> {
  columns: ColumnConfig<T>[]
  state: TableState
  dispatch: Dispatch<TableAction>
  renderFilter?: (field: string, dataType: ColumnDataType) => ReactNode
}

export function DataTableHeader<T>({
  columns,
  state,
  dispatch,
  renderFilter,
}: DataTableHeaderProps<T>) {
  const { t } = useTranslation()
  const [openFilter, setOpenFilter] = useState<string | null>(null)

  const getSortForField = (field: string) =>
    state.sorts.find((s) => s.field === field)

  const getFilterForField = (field: string) =>
    state.filters.find((f) => f.field === field)

  return (
    <TableHeader>
      <TableRow>
        {columns.map((col) => {
          const sort = getSortForField(col.field)
          const filter = getFilterForField(col.field)
          const isSortable = col.sortable !== false
          const isFilterable = col.filterable !== false

          const ariaSort: 'ascending' | 'descending' | 'none' | undefined =
            isSortable
              ? sort
                ? sort.direction === 'asc'
                  ? 'ascending'
                  : 'descending'
                : 'none'
              : undefined

          return (
            <TableHead
              key={col.field}
              style={{ minWidth: col.minWidth }}
              aria-sort={ariaSort}
            >
              <div className="flex items-center gap-1">
                {/* Sortable header */}
                {isSortable ? (
                  <button
                    type="button"
                    className="group flex items-center gap-1 cursor-pointer hover:text-foreground transition-colors"
                    title={
                      sort
                        ? sort.direction === 'asc'
                          ? t('dataTable.sort.asc')
                          : t('dataTable.sort.desc')
                        : t('dataTable.sort.sortable')
                    }
                    onClick={() =>
                      dispatch({
                        type: 'TOGGLE_SORT',
                        payload: { field: col.field },
                      })
                    }
                  >
                    <span>{t(col.headerKey)}</span>
                    {sort ? (
                      <>
                        {sort.direction === 'asc' ? (
                          <ArrowUp className="h-3.5 w-3.5 text-foreground" />
                        ) : (
                          <ArrowDown className="h-3.5 w-3.5 text-foreground" />
                        )}
                        {state.sorts.length > 1 && (
                          <span className="text-xs bg-primary text-primary-foreground rounded-full h-4 w-4 flex items-center justify-center">
                            {sort.priority}
                          </span>
                        )}
                      </>
                    ) : (
                      // Unsorted-but-sortable affordance: a faded up-arrow that
                      // becomes visible on hover so the column reads as an
                      // explicit ascending/descending sort control (Req 7.3).
                      <ArrowUp
                        aria-hidden="true"
                        className="h-3.5 w-3.5 text-muted-foreground/40 opacity-0 group-hover:opacity-100 transition-opacity"
                      />
                    )}
                  </button>
                ) : (
                  <span>{t(col.headerKey)}</span>
                )}

                {/* Filter controls */}
                {isFilterable && (
                  <div className="flex items-center ml-auto">
                    <Popover
                      open={openFilter === col.field}
                      onOpenChange={(open) =>
                        setOpenFilter(open ? col.field : null)
                      }
                    >
                      <PopoverTrigger asChild>
                        <Button
                          variant="ghost"
                          size="icon"
                          className={`h-6 w-6 ${filter ? 'text-primary' : 'text-muted-foreground'}`}
                          aria-label={t('dataTable.filter.open', {
                            defaultValue: `Filter ${col.field}`,
                          })}
                        >
                          <Filter className="h-3.5 w-3.5" />
                        </Button>
                      </PopoverTrigger>
                      <PopoverContent
                        className="w-64 p-4 bg-background border border-border shadow-lg"
                        align="start"
                        sideOffset={8}
                      >
                        {renderFilter?.(col.field, col.dataType)}
                      </PopoverContent>
                    </Popover>
                    {filter && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6 text-muted-foreground hover:text-foreground"
                        onClick={() =>
                          dispatch({
                            type: 'CLEAR_FILTER',
                            payload: { field: col.field },
                          })
                        }
                        aria-label={t('dataTable.filter.clear', {
                          defaultValue: `Clear filter ${col.field}`,
                        })}
                      >
                        <X className="h-3.5 w-3.5" />
                      </Button>
                    )}
                  </div>
                )}
              </div>
            </TableHead>
          )
        })}
      </TableRow>
    </TableHeader>
  )
}
