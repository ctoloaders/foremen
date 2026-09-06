// FOR-04-01 task 9.7: unit test for countActiveFilters (applied-summary counts).
//
// Requirement 7.6: the applied summary counts a reference filter with >=1
// selected id as ONE filter; an empty-ids reference filter counts as zero;
// every other filter kind (string/number/date/boolean) counts as one.
import { describe, it, expect } from 'vitest'
import { countActiveFilters } from '../countActiveFilters'
import type { ColumnFilterState } from '../../types'

describe('countActiveFilters (Req 7.6)', () => {
  it('counts a reference filter with >=1 id as one', () => {
    const filters: ColumnFilterState[] = [
      { type: 'reference', field: 'role.name', ids: [5], idPath: 'role.id' },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts a reference filter with several ids as one (not per id)', () => {
    const filters: ColumnFilterState[] = [
      { type: 'reference', field: 'role.name', ids: [5, 7, 9], idPath: 'role.id' },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts an empty-ids reference filter as zero', () => {
    const filters: ColumnFilterState[] = [
      { type: 'reference', field: 'role.name', ids: [], idPath: 'role.id' },
    ]
    expect(countActiveFilters(filters)).toBe(0)
  })

  it('counts a string filter as one', () => {
    const filters: ColumnFilterState[] = [
      { type: 'string', field: 'name', value: 'acme' },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts a number filter as one', () => {
    const filters: ColumnFilterState[] = [
      { type: 'number', field: 'amount', from: 10, to: 20 },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts a date filter as one', () => {
    const filters: ColumnFilterState[] = [
      { type: 'date', field: 'createdAt', from: '2024-01-01', to: '2024-12-31' },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts a boolean filter as one', () => {
    const filters: ColumnFilterState[] = [
      { type: 'boolean', field: 'active', value: true },
    ]
    expect(countActiveFilters(filters)).toBe(1)
  })

  it('counts a mixed set correctly (empty-ids reference excluded)', () => {
    const filters: ColumnFilterState[] = [
      { type: 'string', field: 'name', value: 'acme' },
      { type: 'number', field: 'amount', from: 10 },
      { type: 'reference', field: 'role.name', ids: [5, 7], idPath: 'role.id' },
      { type: 'boolean', field: 'active', value: false },
      { type: 'reference', field: 'manager.name', ids: [], idPath: 'manager.id' },
    ]
    // 4 active (string, number, populated reference, boolean); the empty-ids
    // reference is skipped.
    expect(countActiveFilters(filters)).toBe(4)
  })

  it('counts zero for an empty filters array', () => {
    expect(countActiveFilters([])).toBe(0)
  })
})
