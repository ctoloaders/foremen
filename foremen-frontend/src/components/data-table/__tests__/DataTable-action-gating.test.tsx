// Task 6.4: Example/unit tests for CRUD action gating (FOR-03-07-menu-visibility)
// Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 8.3
//
// These tests exercise the real `DataTable` composed with a page-style
// `rowActions` callback that mirrors the shipped Users/Roles pages: an Edit
// control gated by `(resource, UPDATE)` and a Deactivate/Delete control gated
// by `(resource, DELETE)`, returning `null` when neither is permitted so the
// table can collapse the row-actions column. The audit button is gated inside
// `DataTable` by `(AUDIT, READ)`. Permissions are driven by seeding the real
// Auth_Store (which is what `usePermission()` reads).
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ColumnConfig, PaginatedResponse } from '../types'
import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'

// Desktop breakpoint so the table (not the mobile card list) renders, giving us
// a stable `<tbody><tr>` structure to count action cells against.
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

// Keep the real react-i18next module (i18n.ts wires initReactI18next, pulled in
// transitively via the Auth_Store → api-client → i18n import chain) but override
// useTranslation so `t` returns the key verbatim for assertions.
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, opts?: Record<string, unknown>) =>
        (opts as { defaultValue?: string })?.defaultValue || key,
      i18n: { language: 'ru', changeLanguage: vi.fn() },
    }),
  }
})

import { DataTable } from '../DataTable'

interface TestRow {
  id: number
  name: string
}

const testColumns: ColumnConfig<TestRow>[] = [
  { field: 'id', headerKey: 'columns.id', dataType: 'number', sortable: true, filterable: true },
  { field: 'name', headerKey: 'columns.name', dataType: 'string', sortable: true, filterable: true },
]

const filledResponse: PaginatedResponse<TestRow> = {
  content: [
    { id: 1, name: 'Alpha' },
    { id: 2, name: 'Beta' },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 25,
  first: true,
  last: true,
}

// aria-labels used by the page-style row actions and the composed audit button.
const EDIT_LABEL = 'common.edit'
const DELETE_LABEL = 'common.delete'
const AUDIT_LABEL = 'audit.button.viewAudit'

function seedUser(roleCode: string, permissions: CurrentUser['permissions']): void {
  useAuthStore.setState({
    user: { id: 1, name: 'Test User', email: 'test@example.com', roleCode, permissions },
  })
}

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

/**
 * Builds a page-style `rowActions` callback mirroring UsersPage/RolesListTab:
 * returns `null` when neither UPDATE nor DELETE is granted, otherwise renders
 * an Edit (UPDATE) and/or Delete (DELETE) control. This is what lets the
 * DataTable collapse the row-actions column when nothing is visible.
 */
function makePageRowActions(canUpdate: boolean, canDelete: boolean) {
  return () => {
    if (!canUpdate && !canDelete) return null
    return (
      <div className="flex items-center gap-1">
        {canUpdate && <button aria-label={EDIT_LABEL}>edit</button>}
        {canDelete && <button aria-label={DELETE_LABEL}>del</button>}
      </div>
    )
  }
}

function renderTable(opts: {
  resource?: string
  canUpdate: boolean
  canDelete: boolean
  showAuditButton?: boolean
}) {
  const fetchFn = vi.fn().mockResolvedValue(filledResponse)
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <DataTable<TestRow>
        entityKey="test-entity"
        resource={opts.resource}
        columns={testColumns}
        fetchFn={fetchFn}
        rowActions={makePageRowActions(opts.canUpdate, opts.canDelete)}
        showAuditButton={opts.showAuditButton}
      />
    </QueryClientProvider>,
  )
}

/**
 * The actions column is a trailing `<td>` appended per row only when
 * `hasRowActions` is true (see DataTableBody). With `testColumns.length` data
 * columns, an actions column is present iff every body row has one extra cell.
 */
function actionsColumnPresent(): boolean {
  const rows = document.querySelectorAll('tbody tr')
  if (rows.length === 0) return false
  return Array.from(rows).every(
    (tr) => tr.querySelectorAll('td').length === testColumns.length + 1,
  )
}

describe('DataTable action gating (FOR-03-07)', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ user: null })
  })
  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  // --- Edit gating (Req 6.3) -------------------------------------------------

  it('renders the Edit control when UPDATE is granted', async () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['UPDATE'] }])
    renderTable({ resource: 'USERS', canUpdate: true, canDelete: false, showAuditButton: false })

    await screen.findByText('Alpha')
    expect(screen.getAllByLabelText(EDIT_LABEL)).toHaveLength(2)
  })

  it('hides the Edit control when UPDATE is not granted (Req 6.3)', async () => {
    // Only DELETE granted → the row still has actions (Delete), but no Edit.
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['DELETE'] }])
    renderTable({ resource: 'USERS', canUpdate: false, canDelete: true, showAuditButton: false })

    await screen.findByText('Alpha')
    expect(screen.queryAllByLabelText(EDIT_LABEL)).toHaveLength(0)
    expect(screen.getAllByLabelText(DELETE_LABEL)).toHaveLength(2)
  })

  // --- Delete gating (Req 6.4) -----------------------------------------------

  it('renders the Delete control when DELETE is granted', async () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['DELETE'] }])
    renderTable({ resource: 'USERS', canUpdate: false, canDelete: true, showAuditButton: false })

    await screen.findByText('Alpha')
    expect(screen.getAllByLabelText(DELETE_LABEL)).toHaveLength(2)
  })

  it('hides the Delete control when DELETE is not granted (Req 6.4)', async () => {
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['UPDATE'] }])
    renderTable({ resource: 'USERS', canUpdate: true, canDelete: false, showAuditButton: false })

    await screen.findByText('Alpha')
    expect(screen.queryAllByLabelText(DELETE_LABEL)).toHaveLength(0)
    expect(screen.getAllByLabelText(EDIT_LABEL)).toHaveLength(2)
  })

  // --- Audit gating (Req 6.5) ------------------------------------------------

  it('renders the Audit control when AUDIT READ is granted and resource is set', async () => {
    seedUser('MANAGER', [{ resource: 'AUDIT', operations: ['READ'] }])
    renderTable({ resource: 'USERS', canUpdate: false, canDelete: false, showAuditButton: true })

    await screen.findByText('Alpha')
    expect(screen.getAllByLabelText(AUDIT_LABEL)).toHaveLength(2)
  })

  it('hides the Audit control when AUDIT READ is not granted (Req 6.5)', async () => {
    // UPDATE keeps the actions column alive (so we can prove the audit button
    // specifically is absent, not the whole column).
    seedUser('MANAGER', [{ resource: 'USERS', operations: ['UPDATE'] }])
    renderTable({ resource: 'USERS', canUpdate: true, canDelete: false, showAuditButton: true })

    await screen.findByText('Alpha')
    expect(screen.queryAllByLabelText(AUDIT_LABEL)).toHaveLength(0)
  })

  it('hides the Audit control when the table declares no resource even if AUDIT READ is granted', async () => {
    seedUser('MANAGER', [{ resource: 'AUDIT', operations: ['READ'] }])
    renderTable({ resource: undefined, canUpdate: false, canDelete: false, showAuditButton: true })

    await screen.findByText('Alpha')
    expect(screen.queryAllByLabelText(AUDIT_LABEL)).toHaveLength(0)
  })

  // --- Column collapse (Req 6.6, 8.3) ---------------------------------------

  it('collapses the row-actions column when none of UPDATE/DELETE/AUDIT READ is permitted (Req 6.6, 8.3)', async () => {
    // Granted an unrelated permission only → read-only on this resource.
    seedUser('CLIENT', [{ resource: 'USERS', operations: ['READ'] }])
    renderTable({ resource: 'USERS', canUpdate: false, canDelete: false, showAuditButton: true })

    await screen.findByText('Alpha')

    expect(screen.queryAllByLabelText(EDIT_LABEL)).toHaveLength(0)
    expect(screen.queryAllByLabelText(DELETE_LABEL)).toHaveLength(0)
    expect(screen.queryAllByLabelText(AUDIT_LABEL)).toHaveLength(0)
    // The trailing actions cell must not be rendered on any row.
    expect(actionsColumnPresent()).toBe(false)
  })

  it('keeps the row-actions column when at least the audit button is visible', async () => {
    seedUser('MANAGER', [{ resource: 'AUDIT', operations: ['READ'] }])
    renderTable({ resource: 'USERS', canUpdate: false, canDelete: false, showAuditButton: true })

    await screen.findByText('Alpha')
    expect(actionsColumnPresent()).toBe(true)
  })

  // --- ADMIN bypass (Req 6.7 / all controls visible) -------------------------

  it('ADMIN sees Edit, Delete, and Audit controls (permission bypass)', async () => {
    seedUser('ADMIN', [])
    renderTable({ resource: 'USERS', canUpdate: true, canDelete: true, showAuditButton: true })

    await screen.findByText('Alpha')
    expect(screen.getAllByLabelText(EDIT_LABEL)).toHaveLength(2)
    expect(screen.getAllByLabelText(DELETE_LABEL)).toHaveLength(2)
    expect(screen.getAllByLabelText(AUDIT_LABEL)).toHaveLength(2)
    expect(actionsColumnPresent()).toBe(true)
  })

  it('every action cell holds a properly labelled control for an ADMIN row', async () => {
    seedUser('ADMIN', [])
    renderTable({ resource: 'USERS', canUpdate: true, canDelete: true, showAuditButton: true })

    await screen.findByText('Alpha')
    const rows = document.querySelectorAll('tbody tr')
    rows.forEach((tr) => {
      const cells = tr.querySelectorAll('td')
      const actionCell = cells[cells.length - 1]!
      const scope = within(actionCell as HTMLElement)
      expect(scope.getByLabelText(EDIT_LABEL)).toBeInTheDocument()
      expect(scope.getByLabelText(DELETE_LABEL)).toBeInTheDocument()
      expect(scope.getByLabelText(AUDIT_LABEL)).toBeInTheDocument()
    })
  })
})
