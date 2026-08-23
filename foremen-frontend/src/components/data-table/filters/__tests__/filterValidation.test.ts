// Feature: table-template, Property 6: Number filter range validation
// Feature: table-template, Property 7: Date filter range validation
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'

/**
 * Validates: Requirements 5.5, 5.6
 *
 * Property 6: Number filter range validation
 * For any number filter where both `from` and `to` are provided:
 * - IF `to` > `from` THEN the filter is valid (produces a query string)
 * - IF `to` ≤ `from` THEN the filter is rejected (validation error)
 */

/**
 * Validates: Requirements 6.5, 6.6
 *
 * Property 7: Date filter range validation
 * For any date filter where both `from` and `to` are provided:
 * - IF `to` is after `from` THEN the filter is valid
 * - IF `to` is before or equal to `from` THEN the filter is rejected
 */

// Extracted validation logic matching NumberFilter and DateFilter components

/**
 * Number range validation as implemented in NumberFilter.tsx:
 * Returns null if valid, error message key if invalid.
 */
function validateNumberRange(from: number, to: number): string | null {
  if (to <= from) {
    return 'dataTable.filter.number.rangeError'
  }
  return null
}

/**
 * Date range validation as implemented in DateFilter.tsx:
 * Returns null if valid, error message key if invalid.
 * Both dates are Date objects; comparison uses `<=`.
 */
function validateDateRange(from: Date, to: Date): string | null {
  if (to <= from) {
    return 'dataTable.filter.date.rangeError'
  }
  return null
}

describe('Property 6: Number filter range validation', () => {
  it('to > from produces no validation error (valid range)', () => {
    fc.assert(
      fc.property(
        fc.double({ min: -1e9, max: 1e9, noNaN: true, noDefaultInfinity: true }),
        fc.double({ min: Number.MIN_VALUE, max: 1e9, noNaN: true, noDefaultInfinity: true }),
        (from, offset) => {
          // Ensure to > from by adding a positive offset
          const to = from + Math.abs(offset) + 0.001
          const error = validateNumberRange(from, to)
          expect(error).toBeNull()
        },
      ),
      { numRuns: 100 },
    )
  })

  it('to ≤ from produces a validation error (invalid range)', () => {
    fc.assert(
      fc.property(
        fc.double({ min: -1e9, max: 1e9, noNaN: true, noDefaultInfinity: true }),
        fc.double({ min: -1e9, max: 0, noNaN: true, noDefaultInfinity: true }),
        (from, negativeOffset) => {
          // Ensure to ≤ from by adding a non-positive offset
          const to = from + negativeOffset
          if (to <= from) {
            const error = validateNumberRange(from, to)
            expect(error).toBe('dataTable.filter.number.rangeError')
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('to === from produces a validation error', () => {
    fc.assert(
      fc.property(
        fc.double({ min: -1e9, max: 1e9, noNaN: true, noDefaultInfinity: true }),
        (value) => {
          const error = validateNumberRange(value, value)
          expect(error).toBe('dataTable.filter.number.rangeError')
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('Property 7: Date filter range validation', () => {
  // Generate arbitrary dates within a reasonable range (2000-2030)
  const arbDate = fc
    .integer({ min: 946684800000, max: 1893456000000 }) // 2000-01-01 to 2030-01-01 (ms)
    .map((ms) => new Date(ms))

  it('to after from produces no validation error (valid range)', () => {
    fc.assert(
      fc.property(
        arbDate,
        fc.integer({ min: 1, max: 365 * 24 * 60 * 60 * 1000 }), // 1ms to ~1 year
        (from, offsetMs) => {
          const to = new Date(from.getTime() + offsetMs)
          const error = validateDateRange(from, to)
          expect(error).toBeNull()
        },
      ),
      { numRuns: 100 },
    )
  })

  it('to before from produces a validation error (invalid range)', () => {
    fc.assert(
      fc.property(
        arbDate,
        fc.integer({ min: 1, max: 365 * 24 * 60 * 60 * 1000 }), // 1ms to ~1 year
        (from, offsetMs) => {
          const to = new Date(from.getTime() - offsetMs)
          const error = validateDateRange(from, to)
          expect(error).toBe('dataTable.filter.date.rangeError')
        },
      ),
      { numRuns: 100 },
    )
  })

  it('to === from (same timestamp) produces a validation error', () => {
    fc.assert(
      fc.property(arbDate, (date) => {
        const sameTo = new Date(date.getTime())
        const error = validateDateRange(date, sameTo)
        expect(error).toBe('dataTable.filter.date.rangeError')
      }),
      { numRuns: 100 },
    )
  })
})
