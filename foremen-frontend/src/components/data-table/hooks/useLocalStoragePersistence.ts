import { useEffect, useRef, type Dispatch } from 'react'

import type { TableState } from '../types'
import type { TableAction } from './useTableState'

const STORAGE_PREFIX = 'foremen:table:'
const DEBOUNCE_MS = 500

/**
 * Persists table state to localStorage under key `foremen:table:{entityKey}`.
 *
 * - On mount: reads persisted state and dispatches RESTORE_STATE if found
 * - On state change: debounced write (500ms) to localStorage
 * - Graceful degradation: catches localStorage errors silently
 */
export function useLocalStoragePersistence(
  entityKey: string,
  state: TableState,
  dispatch: Dispatch<TableAction>
): void {
  const storageKey = `${STORAGE_PREFIX}${entityKey}`
  const isInitialMount = useRef(true)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // On mount: restore state from localStorage
  useEffect(() => {
    try {
      const stored = localStorage.getItem(storageKey)
      if (stored) {
        const parsed = JSON.parse(stored) as TableState
        dispatch({ type: 'RESTORE_STATE', payload: parsed })
      }
    } catch {
      // Graceful degradation: localStorage unavailable or invalid JSON
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey])

  // On state change: debounced write to localStorage
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false
      return
    }

    if (debounceTimer.current) {
      clearTimeout(debounceTimer.current)
    }

    debounceTimer.current = setTimeout(() => {
      try {
        // If state is default (no sorts, no filters, no search), remove the key
        const isDefault =
          state.sorts.length === 0 &&
          state.filters.length === 0 &&
          state.search === ''

        if (isDefault) {
          localStorage.removeItem(storageKey)
        } else {
          localStorage.setItem(storageKey, JSON.stringify(state))
        }
      } catch {
        // Graceful degradation: localStorage unavailable or quota exceeded
      }
    }, DEBOUNCE_MS)

    return () => {
      if (debounceTimer.current) {
        clearTimeout(debounceTimer.current)
      }
    }
  }, [state, storageKey])
}
