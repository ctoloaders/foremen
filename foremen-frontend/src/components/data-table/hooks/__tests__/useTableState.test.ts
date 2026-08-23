// Feature: table-template, Property 4: Sort priority is a valid permutation (priorities contiguous 1..N, no duplicates)
// Feature: table-template, Property 5: Sort cycle is idempotent after three clicks
// Feature: table-template, Property 9: Clear all resets to default state
// Feature: table-template, Property 10: localStorage persistence round-trip
// Feature: table-template, Property 11: Pagination reset on state change
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { tableReducer, type TableAction } from '../useTableState'
import type { TableState, ColumnFilterState } from '../../types'

// --- Shared Arbitraries ---

/** Valid field name: lowercase letter followed by alphanumeric */
const fieldArb = fc.stringMatching(/^[a-z][a-zA-Z0-9]{0,14}$/)

/** Safe string value for search/filter values */
const safeValueArb = fc.stringMatching(/^[a-zA-Z0-9]{1,10}$/)

/** Default state factory */
function createDefaultState(size: number = 25): TableState {
  return {
    page: 0,
    size,
    sorts: [],
    filters: [],
    search: '',
  }
}

/** Arbitrary for a valid string filter */
const stringFilterArb: fc.Arbitrary<ColumnFilterState> = fc
  .tuple(fieldArb, safeValueArb)
  .map(([field, value]) => ({ type: 'string' as const, field, value }))

/** Arbitrary for a valid number filter */
const numberFilterArb: fc.Arbitrary<ColumnFilterState> = fc
  .tuple(fieldArb, fc.integer({ min: 0, max: 500 }), fc.integer({ min: 501, max: 1000 }))
  .map(([field, from, to]) => ({ type: 'number' as const, field, from, to }))

/** Arbitrary for a valid date filter — uses integer timestamps to avoid invalid Date edge cases */
const dateFilterArb: fc.Arbitrary<ColumnFilterState> = fc
  .tuple(
    fieldArb,
    fc.integer({
      min: new Date('2020-01-01').getTime(),
      max: new Date('2024-06-01').getTime(),
    }),
  )
  .map(([field, ts]) => ({
    type: 'date' as const,
    field,
    from: new Date(ts).toISOString().split('T')[0],
    to: undefined,
  }))

/** Arbitrary for any column filter */
const filterArb: fc.Arbitrary<ColumnFilterState> = fc.oneof(
  stringFilterArb,
  numberFilterArb,
  dateFilterArb,
)

/** Arbitrary for a valid TableState with random content */
const tableStateArb: fc.Arbitrary<TableState> = fc
  .tuple(
    fc.integer({ min: 0, max: 100 }), // page
    fc.constantFrom(10, 25, 50), // size
    fc.uniqueArray(fieldArb, { minLength: 0, maxLength: 4 }), // sort fields
    fc.array(filterArb, { minLength: 0, maxLength: 3 }), // filters
    fc.oneof(safeValueArb, fc.constant('')), // search
  )
  .map(([page, size, sortFields, filters, search]) => ({
    page,
    size,
    sorts: sortFields.map((field, i) => ({
      field,
      direction: (i % 2 === 0 ? 'asc' : 'desc') as 'asc' | 'desc',
      priority: i + 1,
    })),
    filters,
    search,
  }))

// =============================================================================
// Property 4: Sort priority is a valid permutation (priorities contiguous 1..N)
// =============================================================================

/**
 * **Validates: Requirements 3.4, 3.5, 3.7**
 *
 * Property 4: Sort priority is a valid permutation
 * For any sequence of TOGGLE_SORT actions on different fields, the resulting sorts
 * have contiguous priorities (1..N) with no duplicates and each field appears at most once.
 */
describe('tableReducer — Property 4: Sort priority is a valid permutation', () => {
  // Generate a random sequence of TOGGLE_SORT actions on unique and repeated fields
  const toggleSequenceArb = fc
    .tuple(
      fc.uniqueArray(fieldArb, { minLength: 2, maxLength: 6 }),
      fc.array(fc.integer({ min: 0, max: 5 }), { minLength: 1, maxLength: 15 }),
    )
    .map(([fields, indices]) => ({
      fields,
      actions: indices.map((i) => ({
        type: 'TOGGLE_SORT' as const,
        payload: { field: fields[i % fields.length]! },
      })),
    }))

  it('produces contiguous priorities 1..N with no duplicates after any toggle sequence', () => {
    fc.assert(
      fc.property(toggleSequenceArb, ({ actions }) => {
        let state = createDefaultState()
        for (const action of actions) {
          state = tableReducer(state, action)
        }

        const { sorts } = state
        const n = sorts.length

        // Priorities should be 1..N
        const priorities = sorts.map((s) => s.priority).sort((a, b) => a - b)
        const expected = Array.from({ length: n }, (_, i) => i + 1)
        expect(priorities).toEqual(expected)

        // No duplicate fields
        const fields = sorts.map((s) => s.field)
        expect(new Set(fields).size).toBe(n)
      }),
      { numRuns: 100 },
    )
  })

  it('each field appears at most once in the sorts list', () => {
    fc.assert(
      fc.property(toggleSequenceArb, ({ actions }) => {
        let state = createDefaultState()
        for (const action of actions) {
          state = tableReducer(state, action)
        }

        const fields = state.sorts.map((s) => s.field)
        expect(new Set(fields).size).toBe(fields.length)
      }),
      { numRuns: 100 },
    )
  })
})

// =============================================================================
// Property 5: Sort cycle is idempotent after three clicks
// =============================================================================

/**
 * **Validates: Requirements 3.2, 3.7**
 *
 * Property 5: Sort cycle is idempotent after three clicks
 * For any single field, applying TOGGLE_SORT three times from unsorted state
 * returns to unsorted (field not in sorts list): unsorted → asc → desc → unsorted.
 */
describe('tableReducer — Property 5: Sort cycle is idempotent after three clicks', () => {
  it('three toggles on the same field from unsorted returns to unsorted', () => {
    fc.assert(
      fc.property(fieldArb, (field) => {
        const initial = createDefaultState()
        const action: TableAction = { type: 'TOGGLE_SORT', payload: { field } }

        // Apply three times
        const after1 = tableReducer(initial, action)
        const after2 = tableReducer(after1, action)
        const after3 = tableReducer(after2, action)

        // After 1 toggle: field should be in sorts with direction 'asc'
        expect(after1.sorts.find((s) => s.field === field)?.direction).toBe('asc')

        // After 2 toggles: field should be in sorts with direction 'desc'
        expect(after2.sorts.find((s) => s.field === field)?.direction).toBe('desc')

        // After 3 toggles: field should NOT be in sorts (unsorted)
        expect(after3.sorts.find((s) => s.field === field)).toBeUndefined()
      }),
      { numRuns: 100 },
    )
  })

  it('three toggles do not affect other existing sorts', () => {
    fc.assert(
      fc.property(
        fc.tuple(fieldArb, fieldArb).filter((pair): pair is [string, string] => pair[0] !== pair[1]),
        ([targetField, otherField]) => {
          // Start with otherField already sorted
          const initial: TableState = {
            ...createDefaultState(),
            sorts: [{ field: otherField, direction: 'asc', priority: 1 }],
          }

          const action: TableAction = { type: 'TOGGLE_SORT', payload: { field: targetField } }

          const after1 = tableReducer(initial, action)
          const after2 = tableReducer(after1, action)
          const after3 = tableReducer(after2, action)

          // otherField should still be sorted throughout
          const otherSort = after3.sorts.find((s) => s.field === otherField)
          expect(otherSort).toBeDefined()
          expect(otherSort!.direction).toBe('asc')

          // targetField should be gone
          expect(after3.sorts.find((s) => s.field === targetField)).toBeUndefined()
        },
      ),
      { numRuns: 100 },
    )
  })
})

// =============================================================================
// Property 9: Clear all resets to default state
// =============================================================================

/**
 * **Validates: Requirements 8.4, 9.6**
 *
 * Property 9: Clear all resets to default state
 * For any TableState with arbitrary filters, sorts, and search, applying CLEAR_ALL
 * produces a state with page=0, empty sorts, empty filters, empty search (size preserved).
 */
describe('tableReducer — Property 9: Clear all resets to default state', () => {
  it('CLEAR_ALL resets to default state preserving size', () => {
    fc.assert(
      fc.property(tableStateArb, (state) => {
        const result = tableReducer(state, { type: 'CLEAR_ALL' })

        expect(result.page).toBe(0)
        expect(result.sorts).toEqual([])
        expect(result.filters).toEqual([])
        expect(result.search).toBe('')
        // size is preserved from the current state
        expect(result.size).toBe(state.size)
      }),
      { numRuns: 100 },
    )
  })

  it('CLEAR_ALL is idempotent — applying twice gives the same result', () => {
    fc.assert(
      fc.property(tableStateArb, (state) => {
        const once = tableReducer(state, { type: 'CLEAR_ALL' })
        const twice = tableReducer(once, { type: 'CLEAR_ALL' })

        expect(twice).toEqual(once)
      }),
      { numRuns: 100 },
    )
  })
})

// =============================================================================
// Property 10: localStorage persistence round-trip
// =============================================================================

/**
 * **Validates: Requirements 9.1, 9.2, 9.3**
 *
 * Property 10: localStorage persistence round-trip
 * For any valid TableState, serializing to JSON and parsing back produces
 * an equivalent TableState.
 */
describe('tableReducer — Property 10: localStorage persistence round-trip', () => {
  it('JSON serialize/deserialize round-trip preserves TableState', () => {
    fc.assert(
      fc.property(tableStateArb, (state) => {
        const serialized = JSON.stringify(state)
        const deserialized: TableState = JSON.parse(serialized)

        expect(deserialized).toEqual(state)
      }),
      { numRuns: 100 },
    )
  })

  it('RESTORE_STATE with deserialized state equals original', () => {
    fc.assert(
      fc.property(tableStateArb, (state) => {
        const serialized = JSON.stringify(state)
        const deserialized: TableState = JSON.parse(serialized)

        // Applying RESTORE_STATE with deserialized data should produce the same state
        const restored = tableReducer(createDefaultState(), {
          type: 'RESTORE_STATE',
          payload: deserialized,
        })

        expect(restored).toEqual(state)
      }),
      { numRuns: 100 },
    )
  })
})

// =============================================================================
// Property 11: Pagination reset on state change
// =============================================================================

/**
 * **Validates: Requirements 2.7, 3.8, 7.4, 11.4**
 *
 * Property 11: Pagination reset on state change
 * For any state with page > 0, applying SET_SEARCH, SET_FILTER, TOGGLE_SORT,
 * SET_PAGE_SIZE, or CLEAR_FILTER SHALL reset page to 0.
 */
describe('tableReducer — Property 11: Pagination reset on state change', () => {
  /** Generate a state with page > 0 */
  const stateWithPageArb = tableStateArb.map((state) => ({
    ...state,
    page: Math.max(1, state.page || 1), // ensure page > 0
  }))

  it('SET_SEARCH resets page to 0', () => {
    fc.assert(
      fc.property(stateWithPageArb, safeValueArb, (state, searchText) => {
        const result = tableReducer(state, {
          type: 'SET_SEARCH',
          payload: searchText,
        })
        expect(result.page).toBe(0)
      }),
      { numRuns: 100 },
    )
  })

  it('SET_FILTER resets page to 0', () => {
    fc.assert(
      fc.property(stateWithPageArb, filterArb, (state, filter) => {
        const result = tableReducer(state, {
          type: 'SET_FILTER',
          payload: filter,
        })
        expect(result.page).toBe(0)
      }),
      { numRuns: 100 },
    )
  })

  it('TOGGLE_SORT resets page to 0', () => {
    fc.assert(
      fc.property(stateWithPageArb, fieldArb, (state, field) => {
        const result = tableReducer(state, {
          type: 'TOGGLE_SORT',
          payload: { field },
        })
        expect(result.page).toBe(0)
      }),
      { numRuns: 100 },
    )
  })

  it('SET_PAGE_SIZE resets page to 0', () => {
    fc.assert(
      fc.property(stateWithPageArb, fc.constantFrom(10, 25, 50), (state, size) => {
        const result = tableReducer(state, {
          type: 'SET_PAGE_SIZE',
          payload: size,
        })
        expect(result.page).toBe(0)
      }),
      { numRuns: 100 },
    )
  })

  it('CLEAR_FILTER resets page to 0', () => {
    fc.assert(
      fc.property(stateWithPageArb, fieldArb, (state, field) => {
        const result = tableReducer(state, {
          type: 'CLEAR_FILTER',
          payload: { field },
        })
        expect(result.page).toBe(0)
      }),
      { numRuns: 100 },
    )
  })
})
