import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'

import { useThemeStore } from '@/stores/theme-store'
import { useAuthStore } from '@/stores/auth-store'
import { useDisplayPreferences } from '@/features/settings/api/query-hooks'
import { mergePreferences } from '@/lib/theme-applicator'

/**
 * useThemeSync syncs backend display preferences for the authenticated user
 * on startup and reverts unsaved live-preview changes when leaving the
 * appearance settings page. The preferences fetch is scoped to the current
 * session user id (from the Auth_Store); while no user is loaded the query
 * stays disabled, so we never request another user's display-preferences.
 */
export function useThemeSync(): void {
  const location = useLocation()
  const prevPathRef = useRef(location.pathname)

  const userId = useAuthStore((s) => s.user?.id)
  const { data: backendPrefs } = useDisplayPreferences(userId)

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
