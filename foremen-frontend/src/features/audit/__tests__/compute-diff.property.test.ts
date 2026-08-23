import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import { computeDiff, formatValue, isErrorSnapshot, getRowBackground, DiffStatus, EXCLUDED_FIELDS } from '../utils/compute-diff'

describe('Feature: FOR-02-06a-audit-comparison-view, Property 1: Key Completeness', () => {
  /**
   * Property 1: Key Completeness
   * For any two nullable snapshot maps (before, after), the set of `field` values
   * returned by computeDiff SHALL equal the union of keys from both maps, excluding
   * base entity fields (EXCLUDED_FIELDS).
   *
   * Validates: Requirements 1.2
   */
  it('computeDiff output fields equal union of input keys minus excluded fields', () => {
    fc.assert(
      fc.property(
        fc.dictionary(fc.string({ minLength: 1 }), fc.jsonValue()),
        fc.dictionary(fc.string({ minLength: 1 }), fc.jsonValue()),
        (before, after) => {
          const result = computeDiff(before, after)
          const resultFields = new Set(result.map((e) => e.field))
          const expectedFields = new Set(
            [...Object.keys(before), ...Object.keys(after)]
              .filter(key => !EXCLUDED_FIELDS.has(key))
          )
          expect(resultFields).toEqual(expectedFields)
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('Feature: FOR-02-06a-audit-comparison-view, Property 2: Value Formatting Consistency', () => {
  /**
   * Property 2: Value Formatting Consistency
   * For any value that is an object or array, formatValue(value) SHALL return exactly JSON.stringify(value).
   * For any null or undefined value, formatValue(value) SHALL return "—".
   * For any primitive value, formatValue(value) SHALL return String(value).
   *
   * Validates: Requirements 1.3, 1.4
   */
  it('formatValue formats objects/arrays as JSON.stringify', () => {
    fc.assert(
      fc.property(
        fc.oneof(
          fc.dictionary(fc.string(), fc.jsonValue()),
          fc.array(fc.jsonValue()),
        ),
        (value) => {
          expect(formatValue(value)).toBe(JSON.stringify(value))
        },
      ),
      { numRuns: 100 },
    )
  })

  it('formatValue formats null/undefined as "—"', () => {
    fc.assert(
      fc.property(
        fc.oneof(fc.constant(null), fc.constant(undefined)),
        (value) => {
          expect(formatValue(value)).toBe('—')
        },
      ),
      { numRuns: 100 },
    )
  })

  it('formatValue formats primitives as String(value)', () => {
    fc.assert(
      fc.property(
        fc.oneof(
          fc.string(),
          fc.integer(),
          fc.double({ noNaN: true }),
          fc.boolean(),
        ),
        (value) => {
          expect(formatValue(value)).toBe(String(value))
        },
      ),
      { numRuns: 100 },
    )
  })

  it('formatValue handles full range of inputs consistently', () => {
    fc.assert(
      fc.property(
        fc.oneof(fc.jsonValue(), fc.constant(null), fc.constant(undefined)),
        (value) => {
          if (typeof value === 'object' && value !== null) {
            expect(formatValue(value)).toBe(JSON.stringify(value))
          } else if (value === null || value === undefined) {
            expect(formatValue(value)).toBe('—')
          } else {
            expect(formatValue(value)).toBe(String(value))
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

/**
 * Property-based tests for isErrorSnapshot utility.
 * Feature: FOR-02-06a-audit-comparison-view
 */
describe('isErrorSnapshot — Property-Based Tests', () => {
  /**
   * **Validates: Requirements 9.1, 9.5**
   *
   * Property 5: Error Snapshot Detection
   * For any snapshot map, if the map has exactly two keys ("class" and "error"),
   * then isErrorSnapshot SHALL return true. For any map with a different key set,
   * it SHALL return false.
   */
  it('Feature: FOR-02-06a-audit-comparison-view, Property 5: Error Snapshot Detection', () => {
    // Test with arbitrary maps — detection must match key set exactly
    const mapArb = fc.dictionary(fc.string(), fc.jsonValue())

    fc.assert(
      fc.property(mapArb, (map) => {
        const keys = Object.keys(map).sort()
        const isError = isErrorSnapshot(map)

        if (keys.length === 2 && keys.includes('class') && keys.includes('error')) {
          expect(isError).toBe(true)
        } else {
          expect(isError).toBe(false)
        }
      }),
      { numRuns: 100 },
    )

    // Also test: exact error snapshot always detected with arbitrary values
    fc.assert(
      fc.property(fc.jsonValue(), fc.jsonValue(), (classVal, errorVal) => {
        const snapshot = { class: classVal, error: errorVal }
        expect(isErrorSnapshot(snapshot)).toBe(true)
      }),
      { numRuns: 100 },
    )

    // Also test: null returns false
    expect(isErrorSnapshot(null)).toBe(false)
  })
})

describe('Feature: FOR-02-06a-audit-comparison-view, Property 3: Default Filter Correctness', () => {
  /**
   * Property 3: Default Filter Correctness
   * For any two non-null snapshot maps, the entries returned by computeDiff filtered
   * to exclude 'unchanged' status SHALL contain only entries where valueBefore differs
   * from valueAfter (by deep equality via JSON.stringify), or where the field is absent
   * in one of the maps. Excluded base entity fields are never present in the output.
   *
   * Validates: Requirements 2.1
   */
  it('filtered non-unchanged entries have different values or field absent from one map', () => {
    fc.assert(
      fc.property(
        fc.dictionary(fc.string({ minLength: 1 }), fc.jsonValue()),
        fc.dictionary(fc.string({ minLength: 1 }), fc.jsonValue()),
        (before, after) => {
          const result = computeDiff(before, after)
          const filtered = result.filter((e) => e.status !== 'unchanged')

          for (const entry of filtered) {
            // Excluded fields should never appear in result
            expect(EXCLUDED_FIELDS.has(entry.field)).toBe(false)

            const inBefore = entry.field in before
            const inAfter = entry.field in after

            if (inBefore && inAfter) {
              // Must be actually different
              expect(JSON.stringify(before[entry.field])).not.toBe(
                JSON.stringify(after[entry.field]),
              )
            } else {
              // Field is in one map but not the other
              expect(inBefore !== inAfter).toBe(true)
            }
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('Feature: FOR-02-06a-audit-comparison-view, Property 4: Classification-to-Color Mapping Completeness', () => {
  /**
   * Property 4: Classification-to-Color Mapping Completeness
   * For any DiffStatus, getRowBackground returns the correct rgba value:
   * yellow for 'changed', green for 'added', red for 'deleted', undefined for 'unchanged'.
   * No other mapping exists.
   *
   * Validates: Requirements 3.1, 3.2, 3.3, 3.4
   */
  it('getRowBackground returns correct rgba for each status', () => {
    const statusArb = fc.oneof(
      fc.constant('changed' as DiffStatus),
      fc.constant('added' as DiffStatus),
      fc.constant('deleted' as DiffStatus),
      fc.constant('unchanged' as DiffStatus),
    )

    fc.assert(
      fc.property(statusArb, (status) => {
        const result = getRowBackground(status)
        switch (status) {
          case 'changed':
            expect(result).toBe('rgba(234, 179, 8, 0.15)')
            break
          case 'added':
            expect(result).toBe('rgba(34, 197, 94, 0.15)')
            break
          case 'deleted':
            expect(result).toBe('rgba(239, 68, 68, 0.15)')
            break
          case 'unchanged':
            expect(result).toBeUndefined()
            break
        }
      }),
      { numRuns: 100 },
    )
  })
})
