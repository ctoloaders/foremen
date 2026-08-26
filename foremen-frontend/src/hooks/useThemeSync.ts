import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'

import { useThemeStore } from '@/stores/theme-store'
import { useDisplayPreferences } from '@/features/settings/api/query-hooks'
import { mergePreferences } from '@/lib/theme-applicator'

/**
 * Placeholder user ID used until authentication is implemented.
 * Once a real auth provider is added, replace this with the actual session user ID.
 */
const PLACEHOLDER_USER_ID: number | undefined = 1

/**
 * useThemeSync — Handles two concerns:
 *
 * 1. On app startup (when a user session exists): fetches display preferences
 *    from the backend, merges with localStorage (backend wins), and updates the
 *    theme store's savedPreferences.
 *
 * 2. On navigate away from `/settings/appearance`: if the user has unsaved
 *    theme changes (live preview), reverts to the last saved preferences.
 *
 * Requirements: 5.5, 8.1, 8.2, 8.3, 8.7
 */
export function useThemeSync(): void {
  const location = useLocation()
  const prevPathRef = useRef(location.pathname)

  // --- 1. Fetch + merge backend preferences on startup ---
  const { data: backendPrefs } = useDisplayPreferences(PLACEHOLDER_USER_ID)

  const mergedOnce = useRef(false)

  useEffect(() => {
    if (backendPrefs && !mergedOnce.current) {
      mergedOnce.current = true
      const state = useThemeStore.getState()
      const localPrefs = {
        themeMode: state.themeMode,
        colorScheme: state.colorScheme,
        fontSize: state.fontSize,
      }
      const merged = mergePreferences(localPrefs, backendPrefs)
      state.updateSavedPreferences(merged)
    }
  }, [backendPrefs])

  // --- 2. Revert on navigate away from settings/appearance ---
  useEffect(() => {
    const prevPath = prevPathRef.current
    prevPathRef.current = location.pathname

    const wasOnSettings = prevPath === '/settings/appearance'
    const leftSettings = wasOnSettings && location.pathname !== '/settings/appearance'

    if (leftSettings) {
      const state = useThemeStore.getState()
      if (state.hasUnsavedChanges()) {
        state.revertToSaved()
      }
    }
  }, [location.pathname])
}
