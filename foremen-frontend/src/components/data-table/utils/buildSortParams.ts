import type { SortState } from '../types'

export function buildSortParams(sorts: SortState[]): string[] {
  return [...sorts]
    .sort((a, b) => a.priority - b.priority)
    .map(s => `${s.field},${s.direction}`)
}
