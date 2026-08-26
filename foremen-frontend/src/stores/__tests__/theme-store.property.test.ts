import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fc from 'fast-check'
import type {
  ThemeMode,
  ColorScheme,
  FontSize,
  ThemePreferences,
} from '@/features/settings/types'

// --- Test Arbitraries ---

const themeModeArb = fc.constantFrom<ThemeMode>('dark', 'light', 'system')
const colorSchemeArb = fc.constantFrom<ColorScheme>(
  'zinc',
  'slate',
  'stone',
  'gray',
  'neutral',
  'blue',
  'green',
  'orange',
  'red',
)
const fontSizeArb = fc.constantFrom<FontSize>('sm', 'default', 'lg', 'xl')

const themePreferencesArb = fc.record({
  themeMode: themeModeArb,
  colorScheme: colorSchemeArb,
  fontSize: fontSizeArb,
})

// --- Mocks ---

beforeEach(() => {
  // Mock localStorage
  const store: Record<string, string> = {}
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => {
      store[key] = value
    },
    removeItem: (key: string) => {
      delete store[key]
    },
    clear: () => {
      Object.keys(store).forEach((k) => delete store[k])
    },
  })

  // Mock document.documentElement for class/style manipulation
  const classListSet = new Set<string>()
  const styleProperties = new Map<string, string>()
  vi.stubGlobal('document', {
    ...document,
    documentElement: {
      classList: {
        add: (cls: string) => classListSet.add(cls),
        remove: (cls: string) => classListSet.delete(cls),
        contains: (cls: string) => classListSet.has(cls),
      },
      style: {
        setProperty: (name: string, value: string) =>
          styleProperties.set(name, value),
        getPropertyValue: (name: string) => styleProperties.get(name) ?? '',
        removeProperty: (name: string) => {
          const val = styleProperties.get(name)
          styleProperties.delete(name)
          return val ?? ''
        },
      },
    },
  })

  // Mock window.matchMedia for resolveMode('system') calls
  vi.stubGlobal('window', {
    ...window,
    matchMedia: (query: string) => ({
      matches: query === '(prefers-color-scheme: dark)' ? false : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    }),
  })

  // Reset module registry so the store is freshly created each time
  vi.resetModules()
})

// Feature: FOR-02-08-theme-settings, Property 8: Revert restores saved preferences
describe('Feature: FOR-02-08-theme-settings, Property 8: Revert restores saved preferences', () => {
  /**
   * Property 8: Revert restores saved preferences
   *
   * For any current preferences state (with arbitrary valid themeMode,
   * colorScheme, fontSize) and any saved preferences state, calling
   * `revertToSaved` SHALL set the current preferences to exactly match
   * the saved preferences, field by field.
   *
   * **Validates: Requirements 5.3, 5.4**
   */
  it('revertToSaved sets current preferences to match savedPreferences', async () => {
    const { useThemeStore } = await import('@/stores/theme-store')

    fc.assert(
      fc.property(
        themePreferencesArb,
        themePreferencesArb,
        (currentPrefs: ThemePreferences, savedPrefs: ThemePreferences) => {
          // Set up: apply current preferences as the live state
          useThemeStore.setState({
            themeMode: currentPrefs.themeMode,
            colorScheme: currentPrefs.colorScheme,
            fontSize: currentPrefs.fontSize,
            savedPreferences: { ...savedPrefs },
          })

          // Act: revert to saved
          useThemeStore.getState().revertToSaved()

          // Assert: current preferences now match saved preferences
          const state = useThemeStore.getState()
          expect(state.themeMode).toBe(savedPrefs.themeMode)
          expect(state.colorScheme).toBe(savedPrefs.colorScheme)
          expect(state.fontSize).toBe(savedPrefs.fontSize)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('revertToSaved preserves savedPreferences unchanged', async () => {
    const { useThemeStore } = await import('@/stores/theme-store')

    fc.assert(
      fc.property(
        themePreferencesArb,
        themePreferencesArb,
        (currentPrefs: ThemePreferences, savedPrefs: ThemePreferences) => {
          useThemeStore.setState({
            themeMode: currentPrefs.themeMode,
            colorScheme: currentPrefs.colorScheme,
            fontSize: currentPrefs.fontSize,
            savedPreferences: { ...savedPrefs },
          })

          useThemeStore.getState().revertToSaved()

          // savedPreferences should remain the same after revert
          const state = useThemeStore.getState()
          expect(state.savedPreferences.themeMode).toBe(savedPrefs.themeMode)
          expect(state.savedPreferences.colorScheme).toBe(
            savedPrefs.colorScheme,
          )
          expect(state.savedPreferences.fontSize).toBe(savedPrefs.fontSize)
        },
      ),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 9: Unsaved changes detection (store-level)
describe('Feature: FOR-02-08-theme-settings, Property 9: Unsaved changes detection (store-level)', () => {
  /**
   * Property 9: Unsaved changes detection
   *
   * For any two `ThemePreferences` objects (current and saved),
   * `hasUnsavedChanges` SHALL return `true` if and only if at least one
   * field (themeMode, colorScheme, or fontSize) differs between current
   * and saved.
   *
   * **Validates: Requirements 5.4**
   */
  it('hasUnsavedChanges returns true iff any field differs between current and saved', async () => {
    const { useThemeStore } = await import('@/stores/theme-store')

    fc.assert(
      fc.property(
        themePreferencesArb,
        themePreferencesArb,
        (currentPrefs: ThemePreferences, savedPrefs: ThemePreferences) => {
          useThemeStore.setState({
            themeMode: currentPrefs.themeMode,
            colorScheme: currentPrefs.colorScheme,
            fontSize: currentPrefs.fontSize,
            savedPreferences: { ...savedPrefs },
          })

          const result = useThemeStore.getState().hasUnsavedChanges()

          // Expected: true iff at least one field differs
          const expected =
            currentPrefs.themeMode !== savedPrefs.themeMode ||
            currentPrefs.colorScheme !== savedPrefs.colorScheme ||
            currentPrefs.fontSize !== savedPrefs.fontSize

          expect(result).toBe(expected)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('hasUnsavedChanges returns false when current matches saved exactly', async () => {
    const { useThemeStore } = await import('@/stores/theme-store')

    fc.assert(
      fc.property(themePreferencesArb, (prefs: ThemePreferences) => {
        // Set current and saved to identical values
        useThemeStore.setState({
          themeMode: prefs.themeMode,
          colorScheme: prefs.colorScheme,
          fontSize: prefs.fontSize,
          savedPreferences: { ...prefs },
        })

        expect(useThemeStore.getState().hasUnsavedChanges()).toBe(false)
      }),
      { numRuns: 100 },
    )
  })

  it('hasUnsavedChanges returns true when only one field differs', async () => {
    const { useThemeStore } = await import('@/stores/theme-store')

    // Generate two different values for each field type
    const differentThemeModeArb = fc
      .tuple(themeModeArb, themeModeArb)
      .filter(([a, b]) => a !== b)
    const differentColorSchemeArb = fc
      .tuple(colorSchemeArb, colorSchemeArb)
      .filter(([a, b]) => a !== b)
    const differentFontSizeArb = fc
      .tuple(fontSizeArb, fontSizeArb)
      .filter(([a, b]) => a !== b)

    // Only themeMode differs
    fc.assert(
      fc.property(
        differentThemeModeArb,
        colorSchemeArb,
        fontSizeArb,
        ([currentMode, savedMode], scheme, size) => {
          useThemeStore.setState({
            themeMode: currentMode,
            colorScheme: scheme,
            fontSize: size,
            savedPreferences: {
              themeMode: savedMode,
              colorScheme: scheme,
              fontSize: size,
            },
          })

          expect(useThemeStore.getState().hasUnsavedChanges()).toBe(true)
        },
      ),
      { numRuns: 100 },
    )

    // Only colorScheme differs
    fc.assert(
      fc.property(
        themeModeArb,
        differentColorSchemeArb,
        fontSizeArb,
        (mode, [currentScheme, savedScheme], size) => {
          useThemeStore.setState({
            themeMode: mode,
            colorScheme: currentScheme,
            fontSize: size,
            savedPreferences: {
              themeMode: mode,
              colorScheme: savedScheme,
              fontSize: size,
            },
          })

          expect(useThemeStore.getState().hasUnsavedChanges()).toBe(true)
        },
      ),
      { numRuns: 100 },
    )

    // Only fontSize differs
    fc.assert(
      fc.property(
        themeModeArb,
        colorSchemeArb,
        differentFontSizeArb,
        (mode, scheme, [currentSize, savedSize]) => {
          useThemeStore.setState({
            themeMode: mode,
            colorScheme: scheme,
            fontSize: currentSize,
            savedPreferences: {
              themeMode: mode,
              colorScheme: scheme,
              fontSize: savedSize,
            },
          })

          expect(useThemeStore.getState().hasUnsavedChanges()).toBe(true)
        },
      ),
      { numRuns: 100 },
    )
  })
})
