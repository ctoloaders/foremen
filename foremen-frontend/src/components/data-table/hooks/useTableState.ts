import { useReducer } from 'react'

import type { ColumnFilterState, SortState, TableState } from '../types'

/** Discriminated union of all table state actions */
export type TableAction =
  | { type: 'SET_SEARCH'; payload: string }
  | { type: 'TOGGLE_SORT'; payload: { field: string } }
  | { type: 'SET_FILTER'; payload: ColumnFilterState }
  | { type: 'CLEAR_FILTER'; payload: { field: string } }
  | { type: 'CLEAR_ALL' }
  | { type: 'SET_PAGE'; payload: number }
  | { type: 'SET_PAGE_SIZE'; payload: number }
  | { type: 'RESTORE_STATE'; payload: TableState }

interface TableStateInit {
  defaultPageSize: number
  defaultSort?: SortState[]
}

function createDefaultState(init: TableStateInit): TableState {
  return {
    page: 0,
    size: init.defaultPageSize,
    sorts: init.defaultSort ?? [],
    filters: [],
    search: '',
  }
}

/** Re-indexes sort priorities to be contiguous 1..N */
function reindexPriorities(sorts: SortState[]): SortState[] {
  return sorts
    .sort((a, b) => a.priority - b.priority)
    .map((s, index) => ({ ...s, priority: index + 1 }))
}

/**
 * Table state reducer implementing all state transitions.
 *
 * TOGGLE_SORT cycle: unsorted → ascending → descending → unsorted
 * - If field not in sorts → add with direction 'asc', priority = max + 1
 * - If field has 'asc' → change to 'desc', keep same priority
 * - If field has 'desc' → remove from sorts, re-index remaining priorities
 * - Always resets page to 0
 */
export function tableReducer(state: TableState, action: TableAction): TableState {
  switch (action.type) {
    case 'SET_SEARCH':
      return {
        ...state,
        search: action.payload,
        page: 0,
      }

    case 'TOGGLE_SORT': {
      const { field } = action.payload
      const existing = state.sorts.find((s) => s.field === field)

      if (!existing) {
        // unsorted → ascending: add with max priority + 1
        const maxPriority = state.sorts.reduce(
          (max, s) => Math.max(max, s.priority),
          0,
        )
        return {
          ...state,
          sorts: [
            ...state.sorts,
            { field, direction: 'asc', priority: maxPriority + 1 },
          ],
          page: 0,
        }
      }

      if (existing.direction === 'asc') {
        // ascending → descending: keep same priority
        return {
          ...state,
          sorts: state.sorts.map((s) =>
            s.field === field ? { ...s, direction: 'desc' as const } : s,
          ),
          page: 0,
        }
      }

      // descending → unsorted: remove and re-index
      const remaining = state.sorts.filter((s) => s.field !== field)
      return {
        ...state,
        sorts: reindexPriorities(remaining),
        page: 0,
      }
    }

    case 'SET_FILTER': {
      const filter = action.payload
      const existingIndex = state.filters.findIndex(
        (f) => f.field === filter.field,
      )
      const newFilters =
        existingIndex >= 0
          ? state.filters.map((f, i) => (i === existingIndex ? filter : f))
          : [...state.filters, filter]

      return {
        ...state,
        filters: newFilters,
        page: 0,
      }
    }

    case 'CLEAR_FILTER':
      return {
        ...state,
        filters: state.filters.filter((f) => f.field !== action.payload.field),
        page: 0,
      }

    case 'CLEAR_ALL':
      return createDefaultState({ defaultPageSize: state.size })

    case 'SET_PAGE':
      return {
        ...state,
        page: action.payload,
      }

    case 'SET_PAGE_SIZE':
      return {
        ...state,
        size: action.payload,
        page: 0,
      }

    case 'RESTORE_STATE':
      return action.payload

    default:
      return state
  }
}

export function useTableState({
  defaultPageSize = 25,
  defaultSort,
}: { defaultPageSize?: number; defaultSort?: SortState[] } = {}) {
  const [state, dispatch] = useReducer(
    tableReducer,
    { defaultPageSize, defaultSort },
    createDefaultState,
  )
  return { state, dispatch }
}
