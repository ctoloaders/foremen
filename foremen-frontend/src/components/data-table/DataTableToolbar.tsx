import { useState, useEffect, useCallback, type Dispatch } from 'react'
import { useTranslation } from 'react-i18next'
import { Search, FilterX } from 'lucide-react'

import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
  TooltipProvider,
} from '@/components/ui/tooltip'

import type { TableState } from './types'
import type { TableAction } from './hooks/useTableState'

interface DataTableToolbarProps {
  state: TableState
  dispatch: Dispatch<TableAction>
}

export function DataTableToolbar({ state, dispatch }: DataTableToolbarProps) {
  const { t } = useTranslation()
  const [searchInput, setSearchInput] = useState(state.search)

  // Sync internal state with external state (e.g., after RESTORE_STATE or CLEAR_ALL)
  useEffect(() => {
    setSearchInput(state.search)
  }, [state.search])

  // Debounced search dispatch (300ms)
  useEffect(() => {
    const timer = setTimeout(() => {
      if (searchInput !== state.search) {
        dispatch({ type: 'SET_SEARCH', payload: searchInput })
      }
    }, 300)
    return () => clearTimeout(timer)
  }, [searchInput, dispatch, state.search])

  const hasActiveFilters = state.filters.length > 0 || state.search !== ''

  const handleClearAll = useCallback(() => {
    dispatch({ type: 'CLEAR_ALL' })
  }, [dispatch])

  return (
    <div className="flex items-center gap-2 mb-4">
      <div className="relative flex-1 max-w-sm">
        <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder={t('dataTable.search.placeholder')}
          className="pl-9"
          aria-label={t('dataTable.search.placeholder')}
        />
      </div>

      {state.filters.length > 0 && (
        <span className="text-sm text-muted-foreground">
          {t('dataTable.filters.activeCount', { count: state.filters.length })}
        </span>
      )}

      {hasActiveFilters && (
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                onClick={handleClearAll}
                aria-label={t('dataTable.filters.clearAll')}
              >
                <FilterX className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>{t('dataTable.filters.clearAll')}</p>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      )}
    </div>
  )
}
