// Feature: table-template, Property 8: Dot-notation field resolution
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { resolveFieldValue } from '../resolveFieldValue'

/**
 * **Validates: Requirements 1.3, 3.4, 3.5, 3.6**
 *
 * Property 8: Dot-notation field resolution
 * For any nested object and any valid dot-notation path,
 * resolveFieldValue SHALL return the value at the terminal key,
 * or undefined if any intermediate key is missing.
 */
describe('resolveFieldValue — Property 8: Dot-notation field resolution', () => {
  // Arbitrary for a valid object key (non-empty, identifier-like)
  const keyArb = fc.stringMatching(/^[a-z][a-zA-Z0-9]{0,9}$/)

  // Arbitrary for a leaf value (various primitive types)
  const leafArb = fc.oneof(
    fc.string(),
    fc.integer(),
    fc.double({ noNaN: true }),
    fc.boolean()
  )

  // Generate a nested object with a known path to a leaf value (depth 1..4)
  const nestedObjectWithPathArb = fc
    .integer({ min: 1, max: 4 })
    .chain((depth) => {
      return fc
        .tuple(
          fc.array(keyArb, { minLength: depth, maxLength: depth }),
          leafArb
        )
        .filter(([keys]) => keys.every((k) => k.length > 0))
        .map(([keys, leaf]) => {
          // Build the nested object from inside out
          let obj: Record<string, unknown> = {}
          obj[keys[keys.length - 1]!] = leaf

          for (let i = keys.length - 2; i >= 0; i--) {
            const wrapper: Record<string, unknown> = {}
            wrapper[keys[i]!] = obj
            obj = wrapper
          }

          const path = keys.join('.')
          return { obj, path, expectedValue: leaf }
        })
    })

  it('resolves value at valid dot-notation path for nested objects (depth 1..4)', () => {
    fc.assert(
      fc.property(nestedObjectWithPathArb, ({ obj, path, expectedValue }) => {
        const result = resolveFieldValue(obj, path)
        expect(result).toEqual(expectedValue)
      }),
      { numRuns: 100 }
    )
  })

  it('returns undefined for invalid paths (path does not exist in object)', () => {
    fc.assert(
      fc.property(
        nestedObjectWithPathArb,
        keyArb.filter((k) => k.length > 0),
        ({ obj }, extraKey) => {
          // Use a non-existent root key to create an invalid path
          const invalidPath = `nonExistentRoot${extraKey}.nested`
          const result = resolveFieldValue(obj, invalidPath)
          expect(result).toBeUndefined()
        }
      ),
      { numRuns: 100 }
    )
  })

  it('returns undefined when an intermediate value is null', () => {
    fc.assert(
      fc.property(
        // Need at least 2 keys: one for intermediate (set to null), one beyond it
        fc.array(keyArb.filter((k) => k.length > 0), { minLength: 2, maxLength: 4 }),
        (keys) => {
          // Place null at the FIRST key so the path tries to traverse through null
          // Object structure: { keys[0]: null }
          // Path: keys.join('.') — tries to go deeper through null
          const obj: Record<string, unknown> = { [keys[0]!]: null }
          const path = keys.join('.')
          const result = resolveFieldValue(obj, path)
          expect(result).toBeUndefined()
        }
      ),
      { numRuns: 100 }
    )
  })

  it('returns undefined when an intermediate value is undefined', () => {
    fc.assert(
      fc.property(
        fc.array(keyArb.filter((k) => k.length > 0), { minLength: 2, maxLength: 4 }),
        (keys) => {
          // Empty object — first key resolves to undefined, path tries to go deeper
          const obj: Record<string, unknown> = {}
          const path = keys.join('.')
          const result = resolveFieldValue(obj, path)
          expect(result).toBeUndefined()
        }
      ),
      { numRuns: 100 }
    )
  })

  it('resolves single-level (no dot) field correctly', () => {
    fc.assert(
      fc.property(
        keyArb.filter((k) => k.length > 0),
        leafArb,
        (key, value) => {
          const obj = { [key]: value }
          const result = resolveFieldValue(obj, key)
          expect(result).toEqual(value)
        }
      ),
      { numRuns: 100 }
    )
  })
})
