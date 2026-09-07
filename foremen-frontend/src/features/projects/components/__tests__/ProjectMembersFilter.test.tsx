import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'

import { buildFetchQuery } from '@/components/data-table/utils/buildFetchQuery'
import type { FetchParams } from '@/components/data-table/types'

/**
 * Tests for the general project-team members filter (FOR-04-13 task 12.4).
 *
 * Behavior under test:
 *  - the pure {@link emitMembersFragment} emitter collapses a single selection
 *    to the tilde-wrapped `members.user.id==<id>` form, a multi selection to
 *    `members.user.id~in~<id1,id2,...>`, and an empty selection to `null`
 *    (aligning with the implemented backend query grammar, NOT RSQL);
 *  - the emitted fragment composes verbatim into a `buildFetchQuery` `query`
 *    param (tilde operators preserved on the wire);
 *  - the component fires `onFragmentChange` with the composed fragment on a
 *    selection change, alongside `onChange` with the raw ids.
 *
 * The underlying {@link ReferenceFilter} is mocked to a single button so the
 * component test exercises only the fragment-emitting glue, not the reference
 * options plumbing (covered by ReferenceFilter's own suite).
 */

// --- i18n: return the key verbatim so the label render is deterministic. ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- ReferenceFilter: stub to a button that emits a fixed selection so we can
//     assert the wiring (onChange + onFragmentChange) without the network. ---
const referenceFilterSpy = vi.fn()
vi.mock('@/components/data-table/ReferenceFilter', () => ({
  ReferenceFilter: (props: { onChange?: (ids: number[]) => void }) => {
    referenceFilterSpy(props)
    return (
      <button
        type="button"
        data-testid="mock-reference-filter"
        onClick={() => props.onChange?.([1, 2, 3])}
      >
        select
      </button>
    )
  },
}))

// --- Import after mocks ---
import {
  ProjectMembersFilter,
  emitMembersFragment,
  MEMBERS_USER_ID_PATH,
} from '../ProjectMembersFilter'

beforeEach(() => {
  vi.clearAllMocks()
})

// ---------------------------------------------------------------------------
// emitMembersFragment (pure)
// ---------------------------------------------------------------------------

describe('emitMembersFragment', () => {
  it('collapses a single selection to the equals form', () => {
    expect(emitMembersFragment([1])).toBe('members.user.id==1')
  })

  it('emits the tilde-wrapped ~in~ form for a multi selection (comma-joined, no parens)', () => {
    expect(emitMembersFragment([1, 2, 3])).toBe('members.user.id~in~1,2,3')
  })

  it('emits null for an empty selection (filter inactive)', () => {
    expect(emitMembersFragment([])).toBeNull()
  })

  it('preserves caller order and does not dedupe', () => {
    expect(emitMembersFragment([3, 1, 3])).toBe('members.user.id~in~3,1,3')
  })

  it('uses the collection-qualified members.user.id path', () => {
    expect(MEMBERS_USER_ID_PATH).toBe('members.user.id')
    expect(emitMembersFragment([7])?.startsWith(MEMBERS_USER_ID_PATH)).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// Composition into a fetch query
// ---------------------------------------------------------------------------

describe('emitMembersFragment — composes into buildFetchQuery', () => {
  const base: FetchParams = { page: 0, size: 25, sort: [] }

  it('carries the ~in~ fragment through the query param with the tilde preserved literally', () => {
    const fragment = emitMembersFragment([1, 2, 3])!
    const qs = buildFetchQuery({ ...base, query: fragment })
    // Only `~` is restored to its literal form by buildFetchQuery; the commas
    // are percent-encoded by URLSearchParams (`%2C`). Decoding round-trips to
    // the original fragment.
    expect(qs).toContain('query=members.user.id~in~1%2C2%2C3')
    expect(qs).not.toContain('%7E')
    const roundTripped = new URLSearchParams(qs).get('query')
    expect(roundTripped).toBe(fragment)
  })

  it('carries the == fragment through the query param (decodes back to the fragment)', () => {
    const fragment = emitMembersFragment([5])!
    const qs = buildFetchQuery({ ...base, query: fragment })
    // `==` is percent-encoded (`%3D%3D`) on the wire but decodes back verbatim.
    expect(qs).toContain('query=members.user.id%3D%3D5')
    expect(new URLSearchParams(qs).get('query')).toBe(fragment)
  })
})

// ---------------------------------------------------------------------------
// Component wiring
// ---------------------------------------------------------------------------

describe('ProjectMembersFilter — onFragmentChange wiring', () => {
  it('fires onChange with the raw ids and onFragmentChange with the composed fragment', async () => {
    const onChange = vi.fn()
    const onFragmentChange = vi.fn()
    const user = userEvent.setup()

    render(
      <ProjectMembersFilter onChange={onChange} onFragmentChange={onFragmentChange} />,
    )

    await user.click(screen.getByTestId('mock-reference-filter'))

    expect(onChange).toHaveBeenCalledWith([1, 2, 3])
    expect(onFragmentChange).toHaveBeenCalledWith('members.user.id~in~1,2,3')
  })
})
