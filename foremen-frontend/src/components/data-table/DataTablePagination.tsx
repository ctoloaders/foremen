import { type Dispatch } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useBreakpoint } from '@/hooks/useBreakpoint'

import type { TableState } from './types'
import type { TableAction } from './hooks/useTableState'

interface DataTablePaginationProps {
  state: TableState
  dispatch: Dispatch<TableAction>
  totalElements: number
  totalPages: number
  isFirst: boolean
  isLast: boolean
  pageSizeOptions?: number[]
}

export function DataTablePagination({
  state,
  dispatch,
  totalElements,
  totalPages,
  isFirst,
  isLast,
  pageSizeOptions = [10, 25, 50],
}: DataTablePaginationProps) {
  const { t } = useTranslation()
  const breakpoint = useBreakpoint()
  const isMobile = breakpoint === 'mobile'

  const from = state.page * state.size + 1
  const to = Math.min((state.page + 1) * state.size, totalElements)

  return (
    <div className="flex items-center justify-between mt-4">
      {/* Left side: showing info (desktop only) */}
      {!isMobile && (
        <span className="text-sm text-muted-foreground">
          {t('dataTable.pagination.showing', { from, to, total: totalElements })}
        </span>
      )}

      {/* Center: pagination buttons */}
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="icon"
          disabled={isFirst}
          onClick={() => dispatch({ type: 'SET_PAGE', payload: state.page - 1 })}
          aria-label={t('dataTable.pagination.previous', { defaultValue: 'Previous page' })}
        >
          <ChevronLeft className="h-4 w-4" />
        </Button>

        {isMobile ? (
          <span className="text-sm">
            {state.page + 1} / {totalPages}
          </span>
        ) : (
          <span className="text-sm text-muted-foreground">
            {state.page + 1} / {totalPages}
          </span>
        )}

        <Button
          variant="outline"
          size="icon"
          disabled={isLast}
          onClick={() => dispatch({ type: 'SET_PAGE', payload: state.page + 1 })}
          aria-label={t('dataTable.pagination.next', { defaultValue: 'Next page' })}
        >
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>

      {/* Right side: page size selector (desktop only) */}
      {!isMobile && (
        <Select
          value={String(state.size)}
          onValueChange={(value) =>
            dispatch({ type: 'SET_PAGE_SIZE', payload: Number(value) })
          }
        >
          <SelectTrigger className="w-[70px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {pageSizeOptions.map((size) => (
              <SelectItem key={size} value={String(size)}>
                {size}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </div>
  )
}
