// Feature: table-template, Property 4: Sort priority is a valid permutation
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { buildSortParams } from '../buildSortParams'
import type { SortDirection, SortState } from '../../types'

/**
 * **Validates: Requirements 1.3, 3.4, 3.5, 3.6**
 *
 * Property 4: Sort priority is a valid permutation
 * For any sequence of SortState items with priorities 1..N,
 * buildSortParams SHALL produce output ordered by priority,
 * with each item appearing exactly once, in format "field,direction".
 */
describe('buildSortParams — Property 4: Sort priority is a valid permutation', () => {
  // Arbitrary for a valid field name (non-empty, no commas)
  const fieldArb = fc.stringMatching(/^[a-z][a-zA-Z0-9.]{0,19}$/)
  const directionArb: fc.Arbitrary<SortDirection> = fc.constantFrom('asc', 'desc')

  // Generate an array of SortState items with unique fields and shuffled priorities 1..N
  const sortStateArrayArb = fc
    .uniqueArray(fieldArb, { minLength: 1, maxLength: 10, comparator: 'IsStrictlyEqual' })
    .chain((fields) => {
      // Generate a random direction for each field
      return fc.tuple(...fields.map(() => directionArb)).map((directions) => {
        // Assign priorities 1..N
        const sortStates: SortState[] = fields.map((field, index) => ({
          field,
          direction: directions[index]!,
          priority: index + 1,
        }))
        return sortStates
      })
    })
    .chain((sortStates) => {
      // Shuffle the array to simulate random input order
      return fc.shuffledSubarray(sortStates, {
        minLength: sortStates.length,
        maxLength: sortStates.length,
      })
    })

  it('output length equals input length', () => {
    fc.assert(
      fc.property(sortStateArrayArb, (sorts) => {
        const result = buildSortParams(sorts)
        expect(result).toHaveLength(sorts.length)
      }),
      { numRuns: 100 }
    )
  })

  it('output is ordered by original priority (priority 1 comes first)', () => {
    fc.assert(
      fc.property(sortStateArrayArb, (sorts) => {
        const result = buildSortParams(sorts)
        // The expected order is sorts sorted by priority ascending
        const expected = [...sorts]
          .sort((a, b) => a.priority - b.priority)
          .map((s) => `${s.field},${s.direction}`)
        expect(result).toEqual(expected)
      }),
      { numRuns: 100 }
    )
  })

  it('each item appears exactly once (no duplicates, no omissions)', () => {
    fc.assert(
      fc.property(sortStateArrayArb, (sorts) => {
        const result = buildSortParams(sorts)
        const uniqueResults = new Set(result)
        expect(uniqueResults.size).toBe(result.length)

        // Every input field should appear in output
        for (const s of sorts) {
          expect(result).toContain(`${s.field},${s.direction}`)
        }
      }),
      { numRuns: 100 }
    )
  })

  it('each output entry has format "field,direction"', () => {
    fc.assert(
      fc.property(sortStateArrayArb, (sorts) => {
        const result = buildSortParams(sorts)
        for (const entry of result) {
          const parts = entry.split(',')
          expect(parts).toHaveLength(2)
          expect(['asc', 'desc']).toContain(parts[1]!)
          expect(parts[0]!.length).toBeGreaterThan(0)
        }
      }),
      { numRuns: 100 }
    )
  })
})
