import type { FetchParams } from '../types'

/**
 * Single source of truth for serializing DataTable {@link FetchParams} into the
 * backend list query string. Every table fetch adapter (users, roles, audit,
 * and any future managed entity) MUST go through this so `page`, `size`,
 * `query` and — crucially — every `sort` clause are serialized identically.
 *
 * Behavior:
 * - `page`/`size` are always set.
 * - `query` is set only when non-empty (a blank query contributes nothing).
 * - each `sort` entry (`"field,direction"`) is appended as a separate `sort`
 *   param, matching Spring Data's repeatable `sort` binding and enabling
 *   multi-sort.
 *
 * The literal tilde operators used by the query grammar (`~ct~`, `~in~`) are
 * preserved on the wire: `~` is an RFC 3986 unreserved character, but
 * `URLSearchParams` percent-encodes it to `%7E`, so it is restored. This keeps
 * the emitted URL aligned with the documented `query=name~ct~...` form.
 *
 * @returns the query string WITHOUT a leading `?`.
 */
export function buildFetchQuery(params: FetchParams): string {
  const searchParams = new URLSearchParams()
  searchParams.set('page', String(params.page))
  searchParams.set('size', String(params.size))
  if (params.query) searchParams.set('query', params.query)
  for (const sortEntry of params.sort) {
    searchParams.append('sort', sortEntry)
  }
  return searchParams.toString().replace(/%7E/gi, '~')
}
