import { useEffect, useRef } from 'react'

import { useThemeStore } from '@/stores/theme-store'
import {
  applyThemeMode,
  applyColorScheme,
  applyFontSize,
} from '@/lib/theme-applicator'

/**
 * useThemeApplicator — Subscribes to the theme store outside React's render cycle
 * and applies CSS changes (dark/light class, custom properties) on every state update.
 *
 * On initial call, applies the current state immediately so the DOM matches
 * the store even if the inline script in index.html ran with stale or missing data.
 *
 * Uses Zustand's `subscribe` method for performance — avoids re-renders on every
 * theme state change since we only need DOM side effects, not React UI updates.
 *
 * Requirements: 1.2, 1.3, 1.4, 1.5, 3.3, 3.5, 4.2, 9.3, 9.4
 */
export function useThemeApplicator(): void {
  const initialized = useRef(false)

  useEffect(() => {
    // Apply current state immediately on mount
    if (!initialized.current) {
      const state = useThemeStore.getState()
      applyThemeMode(state.themeMode, state.resolvedMode)
      applyColorScheme(state.colorScheme, state.resolvedMode)
      applyFontSize(state.fontSize)
      initialized.current = true
    }

    // Subscribe to future state changes (outside React render cycle)
    const unsub = useThemeStore.subscribe((state) => {
      applyThemeMode(state.themeMode, state.resolvedMode)
      applyColorScheme(state.colorScheme, state.resolvedMode)
      applyFontSize(state.fontSize)
    })

    return unsub
  }, [])
}
