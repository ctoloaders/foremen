// Feature: FOR-03-07-menu-visibility, Property 7: Row-actions column collapses iff no row-level action is permitted
// Validates: Requirements 6.3, 6.4, 6.5, 6.6, 8.3
//
// For any Current_User and a table with resource R, the row-actions column is
// rendered if and only if at least one of hasPermission(R, UPDATE),
// hasPermission(R, DELETE), or hasPermission(AUDIT, READ) is true; when all
// three are false, no actions column or header is rendered.
//
// The test drives the REAL DataTable through the REAL usePermission hook
// (seeded via the real Auth_Store) so it exercises the production collapse
// logic, and compares the rendered outcome against an independent oracle
// computed directly from the generated permissions.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, cleanup, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as fc from 'fast-check'

import type { ColumnConfig, PaginatedResponse } from '../types'
import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

// Keep the real react-i18next (initReactI18next is needed transitively) but
// return keys verbatim from useTranslation.
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

const RESOURCE = 'USERS'
const EDIT_LABEL = 'common.edit'
const DELETE_LABEL = 'common.delete'

// --- Arbitraries -------------------------------------------------------------

const resourceCodeArb = fc.constantFrom('USERS', 'AUDIT', 'ROLES', 'RESOURCES', 'X')
const operationCodeArb = fc.constantFrom('CREATE', 'READ', 'UPDATE', 'DELETE', 'z')

const permissionsArb: fc.Arbitrary<CurrentUser['permissions']> = fc.array(
  fc.record({
    resource: resourceCodeArb,
    operations: fc.array(operationCodeArb, { maxLength: 4 }),
  }),
  { maxLength: 6 },
)

// Non-ADMIN role codes plus a couple of ADMIN near-misses that must NOT bypass.
const roleArb: fc.Arbitrary<string> = fc.constantFrom(
  'MANAGER',
  'FOREMAN',
  'CLIENT',
  'admin',
  'ADMIN ',
)

// --- Oracle ------------------------------------------------------------------

function oracleGrants(
  permissions: CurrentUser['permissions'],
  resource: string,
  operation: string,
): boolean {
  return permissions.some(
    (e) => e.resource === resource && e.operations.includes(operation),
  )
}

/** Expected column presence per Property 7 (non-ADMIN path). */
function oracleActionsColumnVisible(permissions: CurrentUser['permissions']): boolean {
  return (
    oracleGrants(permissions, RESOURCE, 'UPDATE') ||
    oracleGrants(permissions, RESOURCE, 'DELETE') ||
    oracleGrants(permissions, 'AUDIT', 'READ')
  )
}

// --- Rendering helpers -------------------------------------------------------

function seedUser(roleCode: string, permissions: CurrentUser['permissions']): void {
  useAuthStore.setState({
    user: { id: 1, name: 'T', email: 't@example.com', roleCode, permissions },
  })
}

/**
 * Page-style rowActions matching the shipped pages: Edit gated by UPDATE,
 * Delete gated by DELETE, returns null when neither is permitted so DataTable
 * can collapse the column.
 */
function makeRowActions(canUpdate: boolean, canDelete: boolean) {
  return () => {
    if (!canUpdate && !canDelete) return null
    return (
      <div>
        {canUpdate && <button aria-label={EDIT_LABEL}>edit</button>}
        {canDelete && <button aria-label={DELETE_LABEL}>del</button>}
      </div>
    )
  }
}

/** True iff every body row carries the trailing actions cell. */
function actionsColumnPresent(): boolean {
  const rows = document.querySelectorAll('tbody tr')
  if (rows.length === 0) return false
  return Array.from(rows).every(
    (tr) => tr.querySelectorAll('td').length === testColumns.length + 1,
  )
}

async function renderAndReadColumn(
  roleCode: string,
  permissions: CurrentUser['permissions'],
): Promise<boolean> {
  seedUser(roleCode, permissions)
  const canUpdate = roleCode === 'ADMIN' || oracleGrants(permissions, RESOURCE, 'UPDATE')
  const canDelete = roleCode === 'ADMIN' || oracleGrants(permissions, RESOURCE, 'DELETE')
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <DataTable<TestRow>
        entityKey="prop-table"
        resource={RESOURCE}
        columns={testColumns}
        fetchFn={vi.fn().mockResolvedValue(filledResponse)}
        rowActions={makeRowActions(canUpdate, canDelete)}
        showAuditButton={true}
      />
    </QueryClientProvider>,
  )
  await screen.findByText('Alpha')
  return actionsColumnPresent()
}

describe('Feature: FOR-03-07-menu-visibility, Property 7: Row-actions column collapses iff no row-level action is permitted', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ user: null })
  })
  afterEach(() => {
    cleanup()
    useAuthStore.setState({ user: null })
  })

  it('column present iff (R,UPDATE) OR (R,DELETE) OR (AUDIT,READ) is granted (non-ADMIN)', async () => {
    await fc.assert(
      fc.asyncProperty(roleArb, permissionsArb, async (roleCode, permissions) => {
        const present = await renderAndReadColumn(roleCode, permissions)
        expect(present).toBe(oracleActionsColumnVisible(permissions))
        cleanup()
        useAuthStore.setState({ user: null })
      }),
      { numRuns: 100 },
    )
  })

  it('ADMIN always shows the row-actions column regardless of permissions', async () => {
    await fc.assert(
      fc.asyncProperty(permissionsArb, async (permissions) => {
        const present = await renderAndReadColumn('ADMIN', permissions)
        expect(present).toBe(true)
        cleanup()
        useAuthStore.setState({ user: null })
      }),
      { numRuns: 100 },
    )
  })
})
