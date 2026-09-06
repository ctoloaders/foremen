import { describe, it, expect } from 'vitest'
import { buildFetchQuery } from '../buildFetchQuery'
import type { FetchParams } from '../../types'

/**
 * Unit tests for the shared FetchParams → query-string serializer used by every
 * table fetch adapter (users, roles, audit). Guarantees `sort` is always sent
 * so all tables sort by one common logic.
 */
describe('buildFetchQuery', () => {
  const base: FetchParams = { page: 0, size: 25, sort: [] }

  it('always sets page and size', () => {
    const qs = buildFetchQuery({ ...base, page: 2, size: 10 })
    expect(qs).toContain('page=2')
    expect(qs).toContain('size=10')
  })

  it('omits query when empty and includes it when present', () => {
    expect(buildFetchQuery(base)).not.toContain('query=')
    const qs = buildFetchQuery({ ...base, query: 'name~ct~acme' })
    // The tilde operator is preserved literally on the wire (not %7E).
    expect(qs).toContain('query=name~ct~acme')
    expect(qs).not.toContain('%7E')
  })

  it('appends a single sort clause as sort=field,dir', () => {
    const qs = buildFetchQuery({ ...base, sort: ['name,asc'] })
    expect(qs).toContain('sort=name%2Casc')
  })

  it('appends every sort clause for multi-sort', () => {
    const qs = buildFetchQuery({ ...base, sort: ['name,asc', 'id,desc'] })
    const sorts = qs.split('&').filter((p) => p.startsWith('sort='))
    expect(sorts).toEqual(['sort=name%2Casc', 'sort=id%2Cdesc'])
  })

  it('emits no sort param for an empty sort array', () => {
    expect(buildFetchQuery(base)).not.toContain('sort=')
  })
})
