import type { TableState, ColumnConfig, ColumnFilterState } from '../types'
import { emitReferenceFragment } from './emitReferenceFragment'

export function buildQueryString(
  state: TableState,
  columns: ColumnConfig[]
): string {
  const parts: string[] = []

  // 1. Global search → OR group
  if (state.search.trim()) {
    const searchableFields = columns
      .filter(c => c.dataType === 'string' && (c.searchable !== false))
      .map(c => c.field)

    if (searchableFields.length > 0) {
      const searchConditions = searchableFields
        .map(f => `${f}~ct~${state.search.trim()}`)
        .join(' OR ')
      parts.push(`(${searchConditions})`)
    }
  }

  // 2. Column filters → AND joined
  for (const filter of state.filters) {
    const condition = buildFilterCondition(filter)
    if (condition) parts.push(condition)
  }

  return parts.join(' AND ')
}

function buildFilterCondition(filter: ColumnFilterState): string | null {
  switch (filter.type) {
    case 'string':
      return filter.value ? `${filter.field}~ct~${filter.value}` : null
    case 'number': {
      const conditions: string[] = []
      if (filter.from != null) conditions.push(`${filter.field}>=${filter.from}`)
      if (filter.to != null) conditions.push(`${filter.field}<=${filter.to}`)
      return conditions.length > 0 ? conditions.join(' AND ') : null
    }
    case 'date': {
      const conditions: string[] = []
      if (filter.from) conditions.push(`${filter.field}>=${filter.from}`)
      if (filter.to) conditions.push(`${filter.field}<=${filter.to}`)
      return conditions.length > 0 ? conditions.join(' AND ') : null
    }
    case 'boolean': {
      if (filter.value === null) return `${filter.field}~null~true`
      return `${filter.field}==${filter.value}`
    }
    case 'reference': {
      // Empty ids → null (no fragment); one id → `idPath==id`; many →
      // `idPath~in~id1,id2,...`. Returning null composes with the existing
      // ' AND ' joiner automatically (Req 5.2, 3.4, 4.2).
      const idFragment = emitReferenceFragment(filter.idPath, filter.ids)
      if (idFragment === null) return null
      // A compound reference column (e.g. the projects-list CLIENT column) pins
      // the same nested join to a constant predicate. When present, AND-append
      // it so the emitted fragment is `<idFragment> AND <extraPredicate>` —
      // reproducing the exact semantics the old bespoke ProjectClientFilter
      // composed by hand (members.user.id~in~<ids> AND members.projectRole.code==CLIENT).
      return filter.extraPredicate
        ? `${idFragment} AND ${filter.extraPredicate}`
        : idFragment
    }
  }
}
