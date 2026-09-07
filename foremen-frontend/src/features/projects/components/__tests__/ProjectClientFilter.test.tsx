import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'

import { buildFetchQuery } from '@/components/data-table/utils/buildFetchQuery'
import type { FetchParams } from '@/components/data-table/types'

/**
 * Tests for the CLIENT-column filter (FOR-04-13 task 12.4).
 *
 * Behavior under test:
 *  - the pure {@link emitClientFragment} emitter composes the user-id half
 *    (single → `members.user.id==<id>`, multi → `members.user.id~in~<...>`)
 *    with the {@link CLIENT_ROLE_PREDICATE} under a single ` AND `, so one
 *    member row must be BOTH the named user AND the CLIENT; an empty selection
 *    emits `null`;
 *  - the compound fragment composes into a `buildFetchQuery` `query` param;
 *  - the component fires `onFragmentChange` with the compound fragment;
 *  - the client filter is INDEPENDENT of the members filter — both can be
 *    active simultaneously and their fragments join under ` AND ` without
 *    interfering (the members half is bare, the client half is CLIENT-scoped).
 *
 * The underlying {@link ReferenceFilter} is mocked so the component test
 * exercises only the compound-fragment glue.
 */

// --- i18n: return the key verbatim. ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- ReferenceFilter: stub emitting a fixed selection on click. ---
vi.mock('@/components/data-table/ReferenceFilter', () => ({
  ReferenceFilter: (props: { onChange?: (ids: number[]) => void }) => (
    <button
      type="button"
      data-testid="mock-reference-filter"
      onClick={() => props.onChange?.([5, 6])}
    >
      select
    </button>
  ),
}))

// --- Import after mocks ---
import {
  ProjectClientFilter,
  emitClientFragment,
  CLIENT_ROLE_PREDICATE,
} from '../ProjectClientFilter'
import { emitMembersFragment } from '../ProjectMembersFilter'

beforeEach(() => {
  vi.clearAllMocks()
})

// ---------------------------------------------------------------------------
// emitClientFragment (pure)
// ---------------------------------------------------------------------------

describe('emitClientFragment', () => {
  it('AND-appends the CLIENT predicate to a single-selection equals fragment', () => {
    expect(emitClientFragment([5])).toBe(
      'members.user.id==5 AND members.projectRole.code==CLIENT',
    )
  })

  it('AND-appends the CLIENT predicate to a multi-selection ~in~ fragment', () => {
    expect(emitClientFragment([5, 6])).toBe(
      'members.user.id~in~5,6 AND members.projectRole.code==CLIENT',
    )
  })

  it('emits null for an empty selection (no CLIENT predicate emitted alone)', () => {
    expect(emitClientFragment([])).toBeNull()
  })

  it('exposes the CLIENT role predicate constant used in composition', () => {
    expect(CLIENT_ROLE_PREDICATE).toBe('members.projectRole.code==CLIENT')
    expect(emitClientFragment([9])?.endsWith(CLIENT_ROLE_PREDICATE)).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// Composition into a fetch query
// ---------------------------------------------------------------------------

describe('emitClientFragment — composes into buildFetchQuery', () => {
  const base: FetchParams = { page: 0, size: 25, sort: [] }

  it('carries the compound fragment through the query param with the tilde preserved literally', () => {
    const fragment = emitClientFragment([5, 6])!
    const qs = buildFetchQuery({ ...base, query: fragment })
    // buildFetchQuery restores only `~` to its literal form; spaces become `+`,
    // commas `%2C`, `==` `%3D%3D`. The whole thing decodes back to the fragment.
    expect(qs).toContain(
      'query=members.user.id~in~5%2C6+AND+members.projectRole.code%3D%3DCLIENT',
    )
    expect(qs).not.toContain('%7E')
    expect(new URLSearchParams(qs).get('query')).toBe(fragment)
  })
})

// ---------------------------------------------------------------------------
// Independence from the members filter
// ---------------------------------------------------------------------------

describe('emitClientFragment — independent of the members filter', () => {
  it('is CLIENT-scoped while the members fragment stays bare; both coexist under AND', () => {
    const membersFragment = emitMembersFragment([1, 2, 3])
    const clientFragment = emitClientFragment([5, 6])

    // The members half is NOT CLIENT-scoped...
    expect(membersFragment).toBe('members.user.id~in~1,2,3')
    expect(membersFragment).not.toContain(CLIENT_ROLE_PREDICATE)
    // ...while the client half is.
    expect(clientFragment).toContain(CLIENT_ROLE_PREDICATE)

    // Both active simultaneously: an owner joins the two fragments with ` AND `,
    // yielding a query where the members condition and the CLIENT condition
    // stand side by side without interfering.
    const composed = [membersFragment, clientFragment].filter(Boolean).join(' AND ')
    expect(composed).toBe(
      'members.user.id~in~1,2,3 AND members.user.id~in~5,6 AND members.projectRole.code==CLIENT',
    )
  })
})

// ---------------------------------------------------------------------------
// Component wiring
// ---------------------------------------------------------------------------

describe('ProjectClientFilter — onFragmentChange wiring', () => {
  it('fires onChange with the raw ids and onFragmentChange with the compound fragment', async () => {
    const onChange = vi.fn()
    const onFragmentChange = vi.fn()
    const user = userEvent.setup()

    render(
      <ProjectClientFilter onChange={onChange} onFragmentChange={onFragmentChange} />,
    )

    await user.click(screen.getByTestId('mock-reference-filter'))

    expect(onChange).toHaveBeenCalledWith([5, 6])
    expect(onFragmentChange).toHaveBeenCalledWith(
      'members.user.id~in~5,6 AND members.projectRole.code==CLIENT',
    )
  })
})
