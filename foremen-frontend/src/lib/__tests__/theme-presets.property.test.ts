// Feature: FOR-02-08-theme-settings, Property 4: Color preset structural completeness
import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import { COLOR_PRESETS, type ColorPreset } from '@/lib/theme-presets'

/**
 * **Validates: Requirements 3.2, 3.4**
 *
 * Property 4: Color preset structural completeness
 * For any color preset in COLOR_PRESETS, the preset SHALL have a `dark` variant
 * and a `light` variant, and each variant SHALL contain non-empty hex string values
 * for all required properties: primary, primaryForeground, accent, accentForeground, and ring.
 */

const HEX_COLOR_REGEX = /^#[0-9a-fA-F]{6}$/

const REQUIRED_VARIANT_FIELDS = [
  'primary',
  'primaryForeground',
  'accent',
  'accentForeground',
  'ring',
] as const

describe('Property 4: Color preset structural completeness', () => {
  it('should have at least 6 color presets (Requirement 3.2)', () => {
    expect(COLOR_PRESETS.length).toBeGreaterThanOrEqual(6)
  })

  it('every preset has dark and light variants with valid hex colors for all required fields', () => {
    fc.assert(
      fc.property(fc.constantFrom(...COLOR_PRESETS), (preset: ColorPreset) => {
        // Verify name, label, and swatchColor are non-empty strings
        expect(preset.name).toBeTruthy()
        expect(typeof preset.name).toBe('string')
        expect(preset.label).toBeTruthy()
        expect(typeof preset.label).toBe('string')
        expect(preset.swatchColor).toBeTruthy()
        expect(typeof preset.swatchColor).toBe('string')

        // Verify both dark and light variants exist
        expect(preset.dark).toBeDefined()
        expect(preset.light).toBeDefined()
        expect(typeof preset.dark).toBe('object')
        expect(typeof preset.light).toBe('object')

        // For each variant, verify all required fields are non-empty hex strings
        for (const variant of ['dark', 'light'] as const) {
          for (const field of REQUIRED_VARIANT_FIELDS) {
            const value = preset[variant][field]
            expect(value).toBeTruthy()
            expect(typeof value).toBe('string')
            expect(value).toMatch(HEX_COLOR_REGEX)
          }
        }
      }),
      { numRuns: 100 },
    )
  })

  it('every preset swatchColor is a valid hex color', () => {
    fc.assert(
      fc.property(fc.constantFrom(...COLOR_PRESETS), (preset: ColorPreset) => {
        expect(preset.swatchColor).toMatch(HEX_COLOR_REGEX)
      }),
      { numRuns: 100 },
    )
  })

  it('every preset name is unique', () => {
    const names = COLOR_PRESETS.map((p) => p.name)
    expect(new Set(names).size).toBe(names.length)
  })
})
