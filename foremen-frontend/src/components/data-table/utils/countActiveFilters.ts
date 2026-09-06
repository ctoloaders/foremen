import type { ColumnFilterState } from '../types'

/**
 * Counts the number of columns with an active filter for the applied-summary
 * chip (Req 7.6).
 *
 * A filter counts as active when it would contribute a fragment to the query:
 * - a reference filter counts only when it has at least one selected id
 *   (`ids.length >= 1`); an empty-ids reference filter is already cleared to no
 *   filter by the commit logic, but this counts defensively;
 * - every other filter entry (string/number/date/boolean) present in
 *   {@link TableState.filters} counts as one.
 *
 * This is a pure function of the filters array so it can be unit/property
 * tested independently of the DataTable rendering.
 */
export function countActiveFilters(filters: ColumnFilterState[]): number {
  return filters.reduce((count, filter) => {
    if (filter.type === 'reference') {
      return filter.ids.length >= 1 ? count + 1 : count
    }
    return count + 1
  }, 0)
}
