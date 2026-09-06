import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import { emitReferenceFragment } from '../emitReferenceFragment'

/**
 * Property tests for the pure reference filter-fragment emitter.
 *
 * The emitter produces TILDE-WRAPPED operators (the implemented backend query
 * grammar in `QueryTokenizer` / `QueryOperator`), NOT RSQL. See design.md
 * "Operator-symbol correction":
 *   - empty ids or empty idPath → null (no fragment)
 *   - single id                → `${idPath}==${id}`
 *   - multiple ids             → `${idPath}~in~${ids.join(',')}` (comma-joined,
 *                                 no parentheses, order-stable — not sorted/deduped)
 */

// A non-empty idPath. Reference id paths are dotted association paths like
// `role.id`; we constrain to identifier-ish, non-empty strings so a truthy
// idPath is guaranteed (the emitter treats empty/falsy idPath as "no filter").
const idPathArb = fc
  .array(fc.stringMatching(/^[A-Za-z][A-Za-z0-9]*$/), { minLength: 1, maxLength: 4 })
  .map((segments) => segments.join('.'))

// Positive integer ids (target-entity primary keys are positive).
const idArb = fc.integer({ min: 1, max: 2_000_000_000 })

describe('Feature: FOR-04-01-table-reference-filter, Property: filter fragment emission', () => {
  /**
   * Single id → equality fragment `${idPath}==${id}`.
   *
   * Validates: Requirements 3.4, 4.3
   */
  it('emits an equality fragment for exactly one id', () => {
    fc.assert(
      fc.property(idPathArb, idArb, (idPath, id) => {
        expect(emitReferenceFragment(idPath, [id])).toBe(`${idPath}==${id}`)
      }),
      { numRuns: 100 },
    )
  })

  /**
   * Multiple ids → comma-joined `~in~` fragment, order-stable (no sort/dedupe).
   *
   * Validates: Requirements 4.2
   */
  it('emits an order-stable comma-joined ~in~ fragment for two or more ids', () => {
    fc.assert(
      fc.property(
        idPathArb,
        fc.array(idArb, { minLength: 2, maxLength: 20 }),
        (idPath, ids) => {
          // Order preserved exactly as supplied — not sorted, not deduped.
          expect(emitReferenceFragment(idPath, ids)).toBe(
            `${idPath}~in~${ids.join(',')}`,
          )
        },
      ),
      { numRuns: 100 },
    )
  })

  /**
   * Empty id set → no fragment (null), for any idPath.
   *
   * Validates: Requirements 3.4, 4.2
   */
  it('emits null (no fragment) for an empty id set', () => {
    fc.assert(
      fc.property(idPathArb, (idPath) => {
        expect(emitReferenceFragment(idPath, [])).toBeNull()
      }),
      { numRuns: 100 },
    )
  })

  /**
   * Empty idPath → no fragment (null), for any id set (empty or not).
   *
   * Validates: Requirements 3.4, 4.2
   */
  it('emits null (no fragment) for an empty idPath regardless of ids', () => {
    fc.assert(
      fc.property(fc.array(idArb, { maxLength: 20 }), (ids) => {
        expect(emitReferenceFragment('', ids)).toBeNull()
      }),
      { numRuns: 100 },
    )
  })
})
