import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'

import type { ColumnConfig, DataTableProps } from '../types'
import { buildQueryString } from '../utils/buildQueryString'
import { buildSortParams } from '../utils/buildSortParams'
import { useTableState } from './useTableState'
import { useLocalStoragePersistence } from './useLocalStoragePersistence'

export function useDataTable<T>(props: DataTableProps<T>) {
  const { entityKey, columns, fetchFn, defaultPageSize = 25, defaultSort } = props

  // State management
  const { state, dispatch } = useTableState({ defaultPageSize, defaultSort })

  // localStorage persistence (debounced 500ms)
  useLocalStoragePersistence(entityKey, state, dispatch)

  // Build query string from state
  const queryString = useMemo(
    () => buildQueryString(state, columns as ColumnConfig[]),
    [state.search, state.filters, columns]
  )

  // Build sort params
  const sortParams = useMemo(
    () => buildSortParams(state.sorts),
    [state.sorts]
  )

  // TanStack Query for data fetching
  const query = useQuery({
    queryKey: [entityKey, 'list', state.page, state.size, sortParams, queryString],
    queryFn: () =>
      fetchFn({
        page: state.page,
        size: state.size,
        sort: sortParams,
        query: queryString || undefined,
      }),
    staleTime: 30_000,
  })

  return { state, dispatch, query, queryString }
}
