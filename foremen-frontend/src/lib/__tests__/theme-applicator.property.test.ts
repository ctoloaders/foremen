import { describe, it, expect, beforeEach } from 'vitest'
import * as fc from 'fast-check'
import {
  applyThemeMode,
  applyColorScheme,
  applyFontSize,
} from '@/lib/theme-applicator'
import { COLOR_PRESETS, FONT_SIZE_MAP } from '@/lib/theme-presets'
import type {
  ThemeMode,
  ResolvedMode,
  ColorScheme,
  FontSize,
} from '@/features/settings/types'

// --- Test Arbitraries ---

const themeModeArb = fc.constantFrom<ThemeMode>('dark', 'light', 'system')
const resolvedModeArb = fc.constantFrom<ResolvedMode>('dark', 'light')
const colorSchemeArb = fc.constantFrom<ColorScheme>(
  ...COLOR_PRESETS.map((p) => p.name),
)
const fontSizeArb = fc.constantFrom<FontSize>('sm', 'default', 'lg', 'xl')

// --- DOM Mock Helpers ---

let classListSet: Set<string>
let styleProperties: Map<string, string>

beforeEach(() => {
  classListSet = new Set<string>()
  styleProperties = new Map<string, string>()

  Object.defineProperty(document, 'documentElement', {
    value: {
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
    writable: true,
    configurable: true,
  })
})

// Feature: FOR-02-08-theme-settings, Property 1: Theme mode resolves to correct HTML class
describe('Feature: FOR-02-08-theme-settings, Property 1: Theme mode resolves to correct HTML class', () => {
  /**
   * Property 1: Theme mode resolves to correct HTML class
   *
   * For any theme mode value ("dark", "light", or "system") and any OS preference
   * (dark or light), the `applyThemeMode` function SHALL add exactly the correct
   * class to `<html>`: "dark" class when resolved mode is dark, no "dark" class
   * when resolved mode is light.
   *
   * **Validates: Requirements 1.2, 1.3, 1.4, 2.3, 2.4**
   */
  it('adds "dark" class when resolvedMode is "dark", removes it when "light"', () => {
    fc.assert(
      fc.property(themeModeArb, resolvedModeArb, (mode, resolvedMode) => {
        // Reset state
        classListSet.clear()

        applyThemeMode(mode, resolvedMode)

        if (resolvedMode === 'dark') {
          expect(classListSet.has('dark')).toBe(true)
        } else {
          expect(classListSet.has('dark')).toBe(false)
        }
      }),
      { numRuns: 100 },
    )
  })

  it('removes "dark" class when resolved mode is "light" even if it was previously set', () => {
    fc.assert(
      fc.property(themeModeArb, (mode) => {
        // Pre-set dark class
        classListSet.clear()
        classListSet.add('dark')

        applyThemeMode(mode, 'light')

        expect(classListSet.has('dark')).toBe(false)
      }),
      { numRuns: 100 },
    )
  })

  it('adds "dark" class when resolved mode is "dark" even if it was not previously set', () => {
    fc.assert(
      fc.property(themeModeArb, (mode) => {
        // Ensure no dark class initially
        classListSet.clear()

        applyThemeMode(mode, 'dark')

        expect(classListSet.has('dark')).toBe(true)
      }),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 5: Color scheme application sets correct CSS properties
describe('Feature: FOR-02-08-theme-settings, Property 5: Color scheme application sets correct CSS properties', () => {
  /**
   * Property 5: Color scheme application sets correct CSS properties
   *
   * For any color scheme name from the defined presets and any resolved mode
   * (dark or light), applying the color scheme SHALL set `--primary`,
   * `--primary-foreground`, `--accent`, `--accent-foreground`, and `--ring`
   * to exactly the values from the preset's corresponding mode variant,
   * and SHALL NOT modify structural properties.
   *
   * **Validates: Requirements 3.3, 3.5, 9.1, 9.5**
   */
  it('sets correct CSS custom properties from preset for the given mode', () => {
    fc.assert(
      fc.property(colorSchemeArb, resolvedModeArb, (scheme, resolvedMode) => {
        styleProperties.clear()

        // Pre-set structural properties to verify they are NOT modified
        const structuralProps = [
          '--background',
          '--foreground',
          '--card',
          '--border',
          '--secondary',
          '--muted',
          '--muted-foreground',
          '--input',
        ]
        for (const prop of structuralProps) {
          styleProperties.set(prop, 'initial-value')
        }

        applyColorScheme(scheme, resolvedMode)

        const preset = COLOR_PRESETS.find((p) => p.name === scheme)!
        const variant = resolvedMode === 'dark' ? preset.dark : preset.light

        // Verify color scheme properties ARE set correctly
        expect(styleProperties.get('--primary')).toBe(variant.primary)
        expect(styleProperties.get('--primary-foreground')).toBe(
          variant.primaryForeground,
        )
        expect(styleProperties.get('--accent')).toBe(variant.accent)
        expect(styleProperties.get('--accent-foreground')).toBe(
          variant.accentForeground,
        )
        expect(styleProperties.get('--ring')).toBe(variant.ring)

        // Verify structural properties are NOT modified
        for (const prop of structuralProps) {
          expect(styleProperties.get(prop)).toBe('initial-value')
        }
      }),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 6: Mode toggle preserves color scheme properties
describe('Feature: FOR-02-08-theme-settings, Property 6: Mode toggle preserves color scheme properties', () => {
  /**
   * Property 6: Mode toggle preserves color scheme properties
   *
   * For any currently applied color scheme, toggling the theme mode (dark<->light)
   * SHALL update structural CSS properties while preserving --primary,
   * --primary-foreground, --accent, --accent-foreground, and --ring unchanged.
   *
   * **Validates: Requirements 2.6, 9.3**
   */
  it('toggling mode preserves color scheme CSS properties unchanged', () => {
    fc.assert(
      fc.property(
        colorSchemeArb,
        resolvedModeArb,
        (scheme, initialMode) => {
          styleProperties.clear()
          classListSet.clear()

          // Apply initial state: color scheme + mode
          applyColorScheme(scheme, initialMode)
          applyThemeMode(initialMode === 'dark' ? 'dark' : 'light', initialMode)

          // Record color scheme property values
          const primaryBefore = styleProperties.get('--primary')
          const primaryFgBefore = styleProperties.get('--primary-foreground')
          const accentBefore = styleProperties.get('--accent')
          const accentFgBefore = styleProperties.get('--accent-foreground')
          const ringBefore = styleProperties.get('--ring')

          // Toggle mode
          const toggledMode: ResolvedMode =
            initialMode === 'dark' ? 'light' : 'dark'
          applyThemeMode(
            toggledMode === 'dark' ? 'dark' : 'light',
            toggledMode,
          )

          // Color scheme properties MUST remain unchanged after mode toggle
          // (applyThemeMode only touches classList, not style properties)
          expect(styleProperties.get('--primary')).toBe(primaryBefore)
          expect(styleProperties.get('--primary-foreground')).toBe(
            primaryFgBefore,
          )
          expect(styleProperties.get('--accent')).toBe(accentBefore)
          expect(styleProperties.get('--accent-foreground')).toBe(
            accentFgBefore,
          )
          expect(styleProperties.get('--ring')).toBe(ringBefore)
        },
      ),
      { numRuns: 100 },
    )
  })
})

// Feature: FOR-02-08-theme-settings, Property 7: Font size maps to correct pixel value
describe('Feature: FOR-02-08-theme-settings, Property 7: Font size maps to correct pixel value', () => {
  /**
   * Property 7: Font size maps to correct pixel value
   *
   * For any valid font size value ("sm", "default", "lg", "xl"),
   * `applyFontSize` SHALL set `--font-size-base` to exactly "12px", "14px",
   * "16px", or "18px" respectively.
   *
   * **Validates: Requirements 4.2, 9.2**
   */
  it('sets --font-size-base to correct pixel value for each font size', () => {
    fc.assert(
      fc.property(fontSizeArb, (size) => {
        styleProperties.clear()

        applyFontSize(size)

        const expected = FONT_SIZE_MAP[size]
        expect(styleProperties.get('--font-size-base')).toBe(expected)
      }),
      { numRuns: 100 },
    )
  })

  it('maps sm->12px, default->14px, lg->16px, xl->18px exhaustively', () => {
    fc.assert(
      fc.property(fontSizeArb, (size) => {
        styleProperties.clear()

        applyFontSize(size)

        const mapping: Record<FontSize, string> = {
          sm: '12px',
          default: '14px',
          lg: '16px',
          xl: '18px',
        }

        expect(styleProperties.get('--font-size-base')).toBe(mapping[size])
      }),
      { numRuns: 100 },
    )
  })
})
