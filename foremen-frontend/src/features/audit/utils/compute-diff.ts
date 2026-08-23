export type DiffStatus = 'changed' | 'added' | 'deleted' | 'unchanged'

export interface DiffEntry {
  field: string
  valueBefore: unknown
  valueAfter: unknown
  status: DiffStatus
}

/** Base entity fields that are excluded from comparison display */
export const EXCLUDED_FIELDS = new Set([
  'createdAt',
  'updatedAt',
  'createdBy',
  'updatedBy',
  'createdDate',
  'updatedDate',
])

/**
 * Normalizes a snapshot value — if it's a string, attempt JSON.parse;
 * if it's already an object, use as-is; if null/undefined, return null.
 */
function normalizeSnapshot(snapshot: Record<string, unknown> | string | null | undefined): Record<string, unknown> | null {
  if (snapshot === null || snapshot === undefined) return null
  if (typeof snapshot === 'string') {
    try {
      const parsed = JSON.parse(snapshot)
      if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>
      }
    } catch {
      // Not valid JSON — treat as empty
    }
    return null
  }
  return snapshot as Record<string, unknown>
}

/**
 * Computes field-level diff between two nullable snapshot maps.
 * Treats null as empty map. Classifies each field as changed, added, deleted, or unchanged
 * using JSON.stringify deep equality. Returns entries sorted alphabetically by field name.
 * Excludes base entity fields (createdAt, updatedAt, etc.) from the output.
 */
export function computeDiff(
  before: Record<string, unknown> | string | null,
  after: Record<string, unknown> | string | null,
): DiffEntry[] {
  const beforeMap = normalizeSnapshot(before) ?? {}
  const afterMap = normalizeSnapshot(after) ?? {}

  const allKeys = new Set(
    [...Object.keys(beforeMap), ...Object.keys(afterMap)]
      .filter(key => !EXCLUDED_FIELDS.has(key))
  )

  const entries: DiffEntry[] = []

  for (const field of allKeys) {
    const hasBefore = field in beforeMap
    const hasAfter = field in afterMap
    const valueBefore = hasBefore ? beforeMap[field] : undefined
    const valueAfter = hasAfter ? afterMap[field] : undefined

    let status: DiffStatus

    if (!hasBefore) {
      status = 'added'
    } else if (!hasAfter) {
      status = 'deleted'
    } else if (JSON.stringify(valueBefore) !== JSON.stringify(valueAfter)) {
      status = 'changed'
    } else {
      status = 'unchanged'
    }

    entries.push({ field, valueBefore, valueAfter, status })
  }

  return entries.sort((a, b) => a.field.localeCompare(b.field))
}

/**
 * Formats a snapshot field value for display.
 * - null/undefined → "—"
 * - object/array → JSON.stringify(value)
 * - primitives → String(value)
 */
export function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—'
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

/**
 * Detects if a snapshot is an error snapshot.
 * An error snapshot has exactly two fields: "class" and "error".
 * Handles string snapshots (JSON) as well as parsed objects.
 */
export function isErrorSnapshot(snapshot: Record<string, unknown> | string | null): boolean {
  const normalized = normalizeSnapshot(snapshot)
  if (!normalized) return false
  const keys = Object.keys(normalized)
  return keys.length === 2 && keys.includes('class') && keys.includes('error')
}

/**
 * Returns a background color (rgba) for a given diff status, or undefined for unchanged.
 */
export function getRowBackground(status: DiffStatus): string | undefined {
  switch (status) {
    case 'changed':
      return 'rgba(234, 179, 8, 0.15)'
    case 'added':
      return 'rgba(34, 197, 94, 0.15)'
    case 'deleted':
      return 'rgba(239, 68, 68, 0.15)'
    case 'unchanged':
      return undefined
  }
}
