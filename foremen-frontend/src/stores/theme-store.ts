import { create } from 'zustand'

import type {
  ColorScheme,
  FontSize,
  ResolvedMode,
  ThemeMode,
  ThemePreferences,
} from '@/features/settings/types'
import { resolveMode, validatePreferences } from '@/lib/theme-applicator'

const STORAGE_KEY = 'foremen-theme-preferences'

/**
 * Reads and validates theme preferences from localStorage.
 * Falls back to defaults on any error (unavailable storage, malformed JSON, invalid values).
 */
function loadFromLocalStorage(): ThemePreferences {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return validatePreferences(null)
    const parsed: unknown = JSON.parse(raw)
    return validatePreferences(parsed)
  } catch {
    // localStorage unavailable or JSON parse error
    return validatePreferences(null)
  }
}

/**
 * Persists the full preferences object to localStorage atomically.
 * Silently ignores errors (quota exceeded, private browsing, etc.).
 */
function persistToLocalStorage(prefs: ThemePreferences): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // localStorage unavailable — operate in memory-only mode
  }
}

interface ThemeState {
  // Current preferences (may differ from saved during live preview)
  themeMode: ThemeMode
  colorScheme: ColorScheme
  fontSize: FontSize

  // Resolved dark/light (accounts for "system" preference)
  resolvedMode: ResolvedMode

  // Last successfully saved state (for reset/revert)
  savedPreferences: ThemePreferences

  // Actions
  setThemeMode: (mode: ThemeMode) => void
  setColorScheme: (scheme: ColorScheme) => void
  setFontSize: (size: FontSize) => void
  updateSavedPreferences: (prefs: ThemePreferences) => void
  revertToSaved: () => void
  hasUnsavedChanges: () => boolean
}

export const useThemeStore = create<ThemeState>((set, get) => {
  const initial = loadFromLocalStorage()
  const initialResolved = resolveMode(initial.themeMode)

  // Set up matchMedia listener for system color scheme changes.
  // When themeMode is 'system', resolvedMode should update reactively.
  if (typeof window !== 'undefined') {
    try {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      const handleChange = () => {
        const state = get()
        if (state.themeMode === 'system') {
          set({ resolvedMode: resolveMode('system') })
        }
      }
      mediaQuery.addEventListener('change', handleChange)
    } catch {
      // matchMedia unavailable — resolvedMode stays static
    }
  }

  return {
    themeMode: initial.themeMode,
    colorScheme: initial.colorScheme,
    fontSize: initial.fontSize,
    resolvedMode: initialResolved,
    savedPreferences: { ...initial },

    setThemeMode: (mode: ThemeMode) => {
      const state = get()
      const prefs: ThemePreferences = {
        themeMode: mode,
        colorScheme: state.colorScheme,
        fontSize: state.fontSize,
      }
      persistToLocalStorage(prefs)
      set({
        themeMode: mode,
        resolvedMode: resolveMode(mode),
      })
    },

    setColorScheme: (scheme: ColorScheme) => {
      const state = get()
      const prefs: ThemePreferences = {
        themeMode: state.themeMode,
        colorScheme: scheme,
        fontSize: state.fontSize,
      }
      persistToLocalStorage(prefs)
      set({ colorScheme: scheme })
    },

    setFontSize: (size: FontSize) => {
      const state = get()
      const prefs: ThemePreferences = {
        themeMode: state.themeMode,
        colorScheme: state.colorScheme,
        fontSize: size,
      }
      persistToLocalStorage(prefs)
      set({ fontSize: size })
    },

    updateSavedPreferences: (prefs: ThemePreferences) => {
      set({
        savedPreferences: { ...prefs },
        themeMode: prefs.themeMode,
        colorScheme: prefs.colorScheme,
        fontSize: prefs.fontSize,
        resolvedMode: resolveMode(prefs.themeMode),
      })
      persistToLocalStorage(prefs)
    },

    revertToSaved: () => {
      const { savedPreferences } = get()
      set({
        themeMode: savedPreferences.themeMode,
        colorScheme: savedPreferences.colorScheme,
        fontSize: savedPreferences.fontSize,
        resolvedMode: resolveMode(savedPreferences.themeMode),
      })
      persistToLocalStorage(savedPreferences)
    },

    hasUnsavedChanges: () => {
      const { themeMode, colorScheme, fontSize, savedPreferences } = get()
      return (
        themeMode !== savedPreferences.themeMode ||
        colorScheme !== savedPreferences.colorScheme ||
        fontSize !== savedPreferences.fontSize
      )
    },
  }
})
