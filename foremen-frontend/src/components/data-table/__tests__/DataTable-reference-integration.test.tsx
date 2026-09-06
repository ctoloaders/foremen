// FOR-04-01 task 7.3: DataTable integration tests for reference-column filtering.
//
// Requirements:
//  - 5.1 reference filtering applies to any table whose metadata declares a
//        reference field, with no per-table code;
//  - 5.2 a reference filter composes with other active filters via AND into a
//        single query string;
//  - 5.3 a reference column renders <ReferenceFilter> instead of the default
//        filter; a table WITHOUT reference fields renders exactly as before.
//
// Two complementary layers are exercised (per design.md "Testing Strategy"):
//  1. buildQueryString composition (the most reliable layer for Req 5.2/3.4/
//     4.2): a ReferenceFilterState + a StringFilterState compose with ' AND ',
//     single ids emit `idPath==id`, many emit `idPath~in~id1,id2`, and an empty
//     selection contributes no fragment. A column set with NO reference
//     descriptor builds an identical query to today (Req 5.3).
//  2. A render-level DataTable test (Req 5.3): the filter popover of a
//     reference column shows the ReferenceFilter dropdown UI (its search
//     placeholder / options) rather than the default StringFilter, while a
//     non-reference string column shows the default string filter; a table with
//     no reference columns keeps the default filters for every column.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'

import type {
  ColumnConfig,
  PaginatedResponse,
  ReferenceInfo,
  TableState,
  ColumnFilterState,
} from '../types'
import { buildQueryString } from '../utils/buildQueryString'

// ---------------------------------------------------------------------------
// Layer 1 — buildQueryString composition (Req 5.2, 3.4, 4.2, 5.3)
//
// This is a pure-function layer: no React, no mocks. It is the most robust
// place to assert AND-composition of a reference filter with other filters.
// ---------------------------------------------------------------------------

const ROLE_REFERENCE: ReferenceInfo = {
  targetResource: 'roles',
  optionsPath: '/api/roles',
  labelField: 'name',
  labelI18n: true,
  idPath: 'role.id',
}

/** Users table: a reference column (role) + a plain string column (email). */
const referenceColumns: ColumnConfig[] = [
  { field: 'email', headerKey: 'columns.email', dataType: 'string', searchable: false },
  { field: 'role.name', headerKey: 'columns.role', dataType: 'string', searchable: false, reference: ROLE_REFERENCE },
]

/** Same shape without any reference descriptor (backward-compat baseline). */
const plainColumns: ColumnConfig[] = [
  { field: 'email', headerKey: 'columns.email', dataType: 'string', searchable: false },
  { field: 'name', headerKey: 'columns.name', dataType: 'string', searchable: false },
]

function makeState(filters: ColumnFilterState[], search = ''): TableState {
  return { page: 0, size: 25, sorts: [], filters, search }
}

describe('buildQueryString — reference filter composition (Req 5.2)', () => {
  it('composes a single-select reference filter with a text filter via AND', () => {
    const state = makeState([
      { type: 'string', field: 'email', value: 'acme' },
      { type: 'reference', field: 'role.name', ids: [5], idPath: 'role.id' },
    ])

    const result = buildQueryString(state, referenceColumns)

    // Both fragments present, joined by ' AND ', reference uses `==` for one id.
    expect(result).toBe('email~ct~acme AND role.id==5')
  })

  it('composes a multi-select reference filter (~in~) with a text filter via AND', () => {
    const state = makeState([
      { type: 'string', field: 'email', value: 'acme' },
      { type: 'reference', field: 'role.name', ids: [5, 7], idPath: 'role.id' },
    ])

    const result = buildQueryString(state, referenceColumns)

    // Multiple ids → tilde-wrapped `~in~` list (comma-joined, no parens).
    expect(result).toBe('email~ct~acme AND role.id~in~5,7')
  })

  it('emits only the reference fragment when it is the sole active filter (single)', () => {
    const state = makeState([
      { type: 'reference', field: 'role.name', ids: [5], idPath: 'role.id' },
    ])

    expect(buildQueryString(state, referenceColumns)).toBe('role.id==5')
  })

  it('emits only the reference fragment when it is the sole active filter (multi)', () => {
    const state = makeState([
      { type: 'reference', field: 'role.name', ids: [5, 7], idPath: 'role.id' },
    ])

    expect(buildQueryString(state, referenceColumns)).toBe('role.id~in~5,7')
  })

  it('contributes no fragment when the reference selection is empty', () => {
    // Empty ids → no fragment: only the text filter survives (no dangling AND).
    const state = makeState([
      { type: 'string', field: 'email', value: 'acme' },
      { type: 'reference', field: 'role.name', ids: [], idPath: 'role.id' },
    ])

    expect(buildQueryString(state, referenceColumns)).toBe('email~ct~acme')
  })

  it('composes two reference filters with other reference filters via AND', () => {
    // Req 5.2 explicitly lists "another reference" as a composable filter.
    const twoRefColumns: ColumnConfig[] = [
      ...referenceColumns,
      {
        field: 'manager.name',
        headerKey: 'columns.manager',
        dataType: 'string',
        searchable: false,
        reference: { ...ROLE_REFERENCE, targetResource: 'users', optionsPath: '/api/users', idPath: 'manager.id' },
      },
    ]
    const state = makeState([
      { type: 'reference', field: 'role.name', ids: [5], idPath: 'role.id' },
      { type: 'reference', field: 'manager.name', ids: [1, 2], idPath: 'manager.id' },
    ])

    expect(buildQueryString(state, twoRefColumns)).toBe('role.id==5 AND manager.id~in~1,2')
  })

  it('table without reference descriptors builds an unchanged query (Req 5.3)', () => {
    // The same string filter over columns that carry NO reference descriptor
    // produces exactly the pre-feature output (a plain ~ct~ fragment). This is
    // the backward-compatibility guarantee: no reference field ⇒ no change.
    const state = makeState([{ type: 'string', field: 'email', value: 'acme' }])

    expect(buildQueryString(state, plainColumns)).toBe('email~ct~acme')
    // And with two string filters, still just AND-joined ~ct~ fragments.
    const twoState = makeState([
      { type: 'string', field: 'email', value: 'acme' },
      { type: 'string', field: 'name', value: 'bob' },
    ])
    expect(buildQueryString(twoState, plainColumns)).toBe('email~ct~acme AND name~ct~bob')
  })
})

// ---------------------------------------------------------------------------
// Layer 2 — DataTable render test (Req 5.3, 5.1)
//
// Mirror the harness used by the existing DataTable render tests: mock
// useBreakpoint (desktop) and react-i18next (return keys verbatim). Also mock
// apiRequest (the ReferenceFilter network seam) so opening the reference
// popover does not hit the network — we only assert the dropdown UI appears.
// ---------------------------------------------------------------------------

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, opts?: Record<string, unknown>) =>
        (opts as { defaultValue?: string })?.defaultValue || key,
      i18n: { language: 'pl', changeLanguage: vi.fn() },
    }),
  }
})

const mockApiRequest = vi.fn()
vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: (...args: unknown[]) => mockApiRequest(...args),
  }
})

// Import components after mocks are registered.
import { DataTable } from '../DataTable'

interface UserRow {
  id: number
  email: string
  role: { id: number; name: string }
}

/** Users table columns: role is a reference column; email is a plain string. */
const usersColumns: ColumnConfig<UserRow>[] = [
  { field: 'email', headerKey: 'columns.email', dataType: 'string', filterable: true, searchable: false },
  {
    field: 'role.name',
    headerKey: 'columns.role',
    dataType: 'string',
    filterable: true,
    searchable: false,
    reference: ROLE_REFERENCE,
  },
]

/** A table with no reference columns (backward-compat baseline). */
const plainRowColumns: ColumnConfig<UserRow>[] = [
  { field: 'email', headerKey: 'columns.email', dataType: 'string', filterable: true, searchable: false },
  { field: 'name', headerKey: 'columns.name', dataType: 'string', filterable: true, searchable: false },
]

const usersResponse: PaginatedResponse<UserRow> = {
  content: [{ id: 1, email: 'a@acme.io', role: { id: 5, name: 'Admin' } }],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 25,
  first: true,
  last: true,
}

function renderWithProviders(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('DataTable — reference column renders ReferenceFilter (Req 5.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    // Options query resolves to a single-page result; enough to render the
    // dropdown chrome (search box + one option) when the popover opens.
    mockApiRequest.mockResolvedValue({
      content: [{ id: 5, name: 'Admin' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
      first: true,
      last: true,
    })
  })

  it('opens the role column filter and shows the ReferenceFilter dropdown, not a StringFilter', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(usersResponse)

    renderWithProviders(
      <DataTable entityKey="users-ref" columns={usersColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('a@acme.io')

    // Open the reference column's filter popover. Filter buttons use the
    // `Filter ${field}` aria-label (see DataTableHeader).
    await user.click(screen.getByLabelText('Filter role.name'))

    // ReferenceFilter chrome appears: its localized search placeholder + an
    // option row — NOT the default StringFilter's placeholder.
    expect(
      await screen.findByPlaceholderText('referenceFilter.searchPlaceholder'),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Admin' })).toBeInTheDocument()
    expect(
      screen.queryByPlaceholderText('dataTable.filter.string.placeholder'),
    ).not.toBeInTheDocument()
  })

  it('opens the non-reference email column filter and shows the default StringFilter', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(usersResponse)

    renderWithProviders(
      <DataTable entityKey="users-ref" columns={usersColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('a@acme.io')

    await user.click(screen.getByLabelText('Filter email'))

    // The default StringFilter renders its placeholder; the reference dropdown
    // does not appear for a plain string column.
    expect(
      await screen.findByPlaceholderText('dataTable.filter.string.placeholder'),
    ).toBeInTheDocument()
    expect(
      screen.queryByPlaceholderText('referenceFilter.searchPlaceholder'),
    ).not.toBeInTheDocument()
    // No options endpoint request is made for a non-reference column.
    expect(mockApiRequest).not.toHaveBeenCalled()
  })
})

describe('DataTable — table without reference fields is unchanged (Req 5.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('every column opens the default StringFilter and never the reference dropdown', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(usersResponse)

    renderWithProviders(
      <DataTable entityKey="plain-table" columns={plainRowColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('a@acme.io')

    // Email column → default string filter.
    await user.click(screen.getByLabelText('Filter email'))
    expect(
      await screen.findByPlaceholderText('dataTable.filter.string.placeholder'),
    ).toBeInTheDocument()
    // Close and open the second column.
    await user.keyboard('{Escape}')

    await user.click(screen.getByLabelText('Filter name'))
    expect(
      await screen.findByPlaceholderText('dataTable.filter.string.placeholder'),
    ).toBeInTheDocument()

    // No reference dropdown UI is ever rendered, and no options request fires.
    expect(
      screen.queryByPlaceholderText('referenceFilter.searchPlaceholder'),
    ).not.toBeInTheDocument()
    expect(mockApiRequest).not.toHaveBeenCalled()
  })
})
