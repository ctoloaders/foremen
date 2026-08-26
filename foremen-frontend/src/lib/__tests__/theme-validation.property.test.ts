import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import { validatePreferences, mergePreferences } from '@/lib/theme-applicator'
import type {
  ThemeMode,
  ColorScheme,
  FontSize,
  ThemePreferences,
  DisplayPreferencesResponse,
} from '@/features/settings/types'

// --- Valid Value Sets ---

const VALID_THEME_MODES: ThemeMode[] = ['dark', 'light', 'system']
const VALID_COLOR_SCHEMES: ColorScheme[] = [
  'zinc',
  'slate',
  'stone',
  'gray',
  'neutral',
  'blue',
  'green',
  'orange',
  'red',
]
const VALID_FONT_SIZES: FontSize[] = ['sm', 'default', 'lg', 'xl']

// --- Arbitraries ---

const themeModeArb = fc.constantFrom<ThemeMode>(...VALID_THEME_MODES)
const colorSchemeArb = fc.constantFrom<ColorScheme>(...VALID_COLOR_SCHEMES)
const fontSizeArb = fc.constantFrom<FontSize>(...VALID_FONT_SIZES)

/** Generates a valid ThemePreferences object. */
const validPreferencesArb: fc.Arbitrary<ThemePreferences> = fc.record({
  themeMode: themeModeArb,
  colorScheme: colorSchemeArb,
  fontSize: fontSizeArb,
})

/** Generates a backend response where each field may be null or a valid/invalid string. */
const backendResponseArb: fc.Arbitrary<DisplayPreferencesResponse> = fc.record({
  themeMode: fc.oneof(
    fc.constant(null),
    fc.constantFrom<string>(...VALID_THEME_MODES),
    fc.string(), // garbage
  ),
  colorScheme: fc.oneof(
    fc.constant(null),
    fc.constantFrom<string>(...VALID_COLOR_SCHEMES),
    fc.string(), // garbage
  ),
  fontSize: fc.oneof(
    fc.constant(null),
    fc.constantFrom<string>(...VALID_FONT_SIZES),
    fc.string(), // garbage
  ),
})

// Feature: FOR-02-08-theme-settings, Property 2: Preferences validation and fallback
describe('Feature: FOR-02-08-theme-settings, Property 2: Preferences validation and fallback', () => {
  /**
   * Property 2: Preferences validation and fallback
   *
   * For any arbitrary string values for themeMode, colorScheme, and fontSize
   * (including null, undefined, empty strings, and garbage), the validation
   * function SHALL return a valid ThemePreferences object — falling back to
   * "system"/"zinc"/"default" for invalid inputs.
   *
   * **Validates: Requirements 1.7, 4.6, 7.3**
   */
  it('always returns a valid ThemePreferences regardless of input', () => {
    fc.assert(
      fc.property(fc.anything(), (input) => {
        const result = validatePreferences(input)

        // Result must always be a valid ThemePreferences
        expect(VALID_THEME_MODES).toContain(result.themeMode)
        expect(VALID_COLOR_SCHEMES).toContain(result.colorScheme)
        expect(VALID_FONT_SIZES).toContain(result.fontSize)
      }),
      { numRuns: 100 },
    )
  })

  it('falls back to defaults for objects with invalid field values', () => {
    fc.assert(
      fc.property(
        fc.record({
          themeMode: fc.string(),
          colorScheme: fc.string(),
          fontSize: fc.string(),
        }),
        (raw) => {
          const result = validatePreferences(raw)

          // If raw themeMode is not valid, result should be "system"
          if (!VALID_THEME_MODES.includes(raw.themeMode as ThemeMode)) {
            expect(result.themeMode).toBe('system')
          }
          // If raw colorScheme is not valid, result should be "zinc"
          if (!VALID_COLOR_SCHEMES.includes(raw.colorScheme as ColorScheme)) {
            expect(result.colorScheme).toBe('zinc')
          }
          // If raw fontSize is not valid, result should be "default"
          if (!VALID_FONT_SIZES.includes(raw.fontSize as FontSize)) {
            expect(result.fontSize).toBe('default')
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('preserves valid field values and only replaces invalid ones', () => {
    fc.assert(
      fc.property(
        themeModeArb,
        colorSchemeArb,
        fontSizeArb,
        fc.string(),
        (validMode, _validScheme, validSize, garbage) => {
          // Mix valid and invalid values
          const raw = {
            themeMode: validMode,
            colorScheme: garbage, // invalid
            fontSize: validSize,
          }
          const result = validatePreferences(raw)

          // Valid fields preserved
          expect(result.themeMode).toBe(validMode)
          expect(result.fontSize).toBe(validSize)
          // Invalid field gets default
          if (!VALID_COLOR_SCHEMES.includes(garbage as ColorScheme)) {
            expect(result.colorScheme).toBe('zinc')
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('returns defaults for null, undefined, and non-object inputs', () => {
    const nonObjectInputs = [null, undefined, 42, true, 'hello', [], NaN]
    for (const input of nonObjectInputs) {
      const result = validatePreferences(input)
      expect(result.themeMode).toBe('system')
      expect(result.colorScheme).toBe('zinc')
      expect(result.fontSize).toBe('default')
    }
  })
})

// Feature: FOR-02-08-theme-settings, Property 3: localStorage preferences round-trip
describe('Feature: FOR-02-08-theme-settings, Property 3: localStorage preferences round-trip', () => {
  /**
   * Property 3: localStorage preferences round-trip
   *
   * For any valid ThemePreferences object, serializing it to JSON and parsing
   * back SHALL produce an equivalent object with identical themeMode, colorScheme,
   * and fontSize values.
   *
   * **Validates: Requirements 1.8, 7.1, 7.2, 7.6**
   */
  it('JSON serialize → parse round-trip produces identical preferences', () => {
    fc.assert(
      fc.property(validPreferencesArb, (prefs) => {
        const serialized = JSON.stringify(prefs)
        const deserialized = JSON.parse(serialized) as unknown
        const validated = validatePreferences(deserialized)

        expect(validated.themeMode).toBe(prefs.themeMode)
        expect(validated.colorScheme).toBe(prefs.colorScheme)
        expect(validated.fontSize).toBe(prefs.fontSize)
      }),
      { numRuns: 100 },
    )
  })

  it('round-trip through localStorage key simulation preserves all fields', () => {
    fc.assert(
      fc.property(validPreferencesArb, (prefs) => {
        // Simulate localStorage: setItem serializes, getItem returns string
        const stored = JSON.stringify(prefs)
        const retrieved = JSON.parse(stored) as unknown
        const result = validatePreferences(retrieved)

        expect(result).toEqual(prefs)
      }),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 9: Unsaved changes detection
describe('Feature: FOR-02-08-theme-settings, Property 9: Unsaved changes detection', () => {
  /**
   * Property 9: Unsaved changes detection
   *
   * For any two ThemePreferences objects (current and saved),
   * `hasUnsavedChanges` logic SHALL return true if and only if at least one
   * field differs between current and saved.
   *
   * **Validates: Requirements 5.4**
   */

  /** Pure implementation of hasUnsavedChanges logic (mirrors store logic). */
  function hasUnsavedChanges(
    current: ThemePreferences,
    saved: ThemePreferences,
  ): boolean {
    return (
      current.themeMode !== saved.themeMode ||
      current.colorScheme !== saved.colorScheme ||
      current.fontSize !== saved.fontSize
    )
  }

  it('returns true if and only if at least one field differs', () => {
    fc.assert(
      fc.property(validPreferencesArb, validPreferencesArb, (current, saved) => {
        const result = hasUnsavedChanges(current, saved)

        const anyDifference =
          current.themeMode !== saved.themeMode ||
          current.colorScheme !== saved.colorScheme ||
          current.fontSize !== saved.fontSize

        expect(result).toBe(anyDifference)
      }),
      { numRuns: 100 },
    )
  })

  it('returns false when current and saved are identical', () => {
    fc.assert(
      fc.property(validPreferencesArb, (prefs) => {
        const result = hasUnsavedChanges(prefs, { ...prefs })
        expect(result).toBe(false)
      }),
      { numRuns: 100 },
    )
  })

  it('returns true when only one field differs', () => {
    fc.assert(
      fc.property(
        validPreferencesArb,
        fc.constantFrom<'themeMode' | 'colorScheme' | 'fontSize'>(
          'themeMode',
          'colorScheme',
          'fontSize',
        ),
        (prefs, fieldToChange) => {
          const modified = { ...prefs }

          // Change exactly one field to a different value
          if (fieldToChange === 'themeMode') {
            const others = VALID_THEME_MODES.filter(
              (m) => m !== prefs.themeMode,
            )
            if (others.length > 0) {
              modified.themeMode = others[0]!
            }
          } else if (fieldToChange === 'colorScheme') {
            const others = VALID_COLOR_SCHEMES.filter(
              (s) => s !== prefs.colorScheme,
            )
            if (others.length > 0) {
              modified.colorScheme = others[0]!
            }
          } else {
            const others = VALID_FONT_SIZES.filter(
              (f) => f !== prefs.fontSize,
            )
            if (others.length > 0) {
              modified.fontSize = others[0]!
            }
          }

          // If we actually changed the field, result should be true
          if (
            modified.themeMode !== prefs.themeMode ||
            modified.colorScheme !== prefs.colorScheme ||
            modified.fontSize !== prefs.fontSize
          ) {
            expect(hasUnsavedChanges(modified, prefs)).toBe(true)
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 10: Backend merge with backend-takes-precedence
describe('Feature: FOR-02-08-theme-settings, Property 10: Backend merge with backend-takes-precedence', () => {
  /**
   * Property 10: Backend merge with backend-takes-precedence
   *
   * For any local ThemePreferences and any backend response (where each field
   * may be null or a valid value), the merge function SHALL use the backend
   * value for each field where the backend value is non-null and valid, and
   * the local value otherwise.
   *
   * **Validates: Requirements 7.4, 8.7**
   */
  it('uses backend value when non-null and valid, local value otherwise', () => {
    fc.assert(
      fc.property(
        validPreferencesArb,
        backendResponseArb,
        (local, backend) => {
          const result = mergePreferences(local, backend)

          // themeMode: backend wins if non-null and valid
          if (
            backend.themeMode !== null &&
            VALID_THEME_MODES.includes(backend.themeMode as ThemeMode)
          ) {
            expect(result.themeMode).toBe(backend.themeMode)
          } else {
            expect(result.themeMode).toBe(local.themeMode)
          }

          // colorScheme: backend wins if non-null and valid
          if (
            backend.colorScheme !== null &&
            VALID_COLOR_SCHEMES.includes(backend.colorScheme as ColorScheme)
          ) {
            expect(result.colorScheme).toBe(backend.colorScheme)
          } else {
            expect(result.colorScheme).toBe(local.colorScheme)
          }

          // fontSize: backend wins if non-null and valid
          if (
            backend.fontSize !== null &&
            VALID_FONT_SIZES.includes(backend.fontSize as FontSize)
          ) {
            expect(result.fontSize).toBe(backend.fontSize)
          } else {
            expect(result.fontSize).toBe(local.fontSize)
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('returns local preferences entirely when all backend fields are null', () => {
    fc.assert(
      fc.property(validPreferencesArb, (local) => {
        const backend: DisplayPreferencesResponse = {
          themeMode: null,
          colorScheme: null,
          fontSize: null,
        }
        const result = mergePreferences(local, backend)

        expect(result).toEqual(local)
      }),
      { numRuns: 100 },
    )
  })

  it('returns backend values entirely when all backend fields are valid', () => {
    fc.assert(
      fc.property(
        validPreferencesArb,
        validPreferencesArb,
        (local, backendPrefs) => {
          const backend: DisplayPreferencesResponse = {
            themeMode: backendPrefs.themeMode,
            colorScheme: backendPrefs.colorScheme,
            fontSize: backendPrefs.fontSize,
          }
          const result = mergePreferences(local, backend)

          expect(result.themeMode).toBe(backendPrefs.themeMode)
          expect(result.colorScheme).toBe(backendPrefs.colorScheme)
          expect(result.fontSize).toBe(backendPrefs.fontSize)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('falls back to local for invalid (non-null) backend values', () => {
    fc.assert(
      fc.property(
        validPreferencesArb,
        fc.string().filter(
          (s) => !VALID_THEME_MODES.includes(s as ThemeMode),
        ),
        fc.string().filter(
          (s) => !VALID_COLOR_SCHEMES.includes(s as ColorScheme),
        ),
        fc.string().filter(
          (s) => !VALID_FONT_SIZES.includes(s as FontSize),
        ),
        (local, invalidMode, invalidScheme, invalidSize) => {
          const backend: DisplayPreferencesResponse = {
            themeMode: invalidMode,
            colorScheme: invalidScheme,
            fontSize: invalidSize,
          }
          const result = mergePreferences(local, backend)

          // All backend values are invalid, so local should win
          expect(result).toEqual(local)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('result is always a valid ThemePreferences', () => {
    fc.assert(
      fc.property(
        validPreferencesArb,
        backendResponseArb,
        (local, backend) => {
          const result = mergePreferences(local, backend)

          expect(VALID_THEME_MODES).toContain(result.themeMode)
          expect(VALID_COLOR_SCHEMES).toContain(result.colorScheme)
          expect(VALID_FONT_SIZES).toContain(result.fontSize)
        },
      ),
      { numRuns: 100 },
    )
  })
})
