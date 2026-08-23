// Task 8.4: Tests for AuditPage and i18n
// Requirements: 3.1, 3.5, 8.4
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { PaginatedResponse } from '@/components/data-table/types'
import type { AuditRecord } from '../types'

// Mock useBreakpoint — default desktop
let mockBreakpoint = 'desktop'
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockBreakpoint,
}))

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (opts as { defaultValue?: string })?.defaultValue || key,
    i18n: { language: 'pl' },
  }),
}))

// Import after mocks
import AuditPage from '../AuditPage'

const filledResponse: PaginatedResponse<AuditRecord> = {
  content: [
    {
      id: 1,
      entityClass: 'RoleEntity',
      entityId: 10,
      operation: 'CREATE',
      performedBy: 'admin',
      performedAt: '2024-01-15T10:30:00',
      snapshotBefore: null,
      snapshotAfter: { code: 'ADMIN', name: 'Admin' },
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 25,
  first: true,
  last: true,
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

describe('AuditPage', () => {
  beforeEach(() => {
    mockBreakpoint = 'desktop'
    localStorage.clear()
    // Mock global fetch for the audit API
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(filledResponse), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })

  it('renders DataTable with correct audit columns', async () => {
    renderWithProviders(<AuditPage />)

    // Wait for column headers to render
    const headers = await screen.findAllByRole('columnheader')
    // auditFullColumns defines 7 columns:
    // id, entityClass, entityId, operation, performedBy, performedAt, changes
    expect(headers.length).toBe(7)

    // Verify all expected header keys are present (mocked t() returns the key)
    expect(screen.getByText('audit.column.id')).toBeInTheDocument()
    expect(screen.getByText('audit.column.entityClass')).toBeInTheDocument()
    expect(screen.getByText('audit.column.entityId')).toBeInTheDocument()
    expect(screen.getByText('audit.column.operation')).toBeInTheDocument()
    expect(screen.getByText('audit.column.performedBy')).toBeInTheDocument()
    expect(screen.getByText('audit.column.performedAt')).toBeInTheDocument()
    expect(screen.getByText('audit.column.changes')).toBeInTheDocument()
  })

  it('DataTable has showAuditButton=false (no audit button rendered in rows)', async () => {
    renderWithProviders(<AuditPage />)

    // Wait for data to load
    await screen.findByText('admin')

    // The audit button tooltip key is 'audit.button.viewAudit'
    // With showAuditButton=false, no audit button should be rendered
    const auditButtons = screen.queryAllByLabelText('audit.button.viewAudit')
    expect(auditButtons).toHaveLength(0)

    // Also check no ScrollText icon button for audit is rendered
    const auditButtonByText = screen.queryByText('audit.button.viewAudit')
    // With showAuditButton=false this should not exist as a button action
    // The button uses a tooltip, so we verify it's absent from the DOM
    expect(auditButtonByText).not.toBeInTheDocument()
  })
})

describe('Audit i18n keys', () => {
  const requiredKeys = [
    'nav.audit',
    'audit.pageTitle',
    'audit.column.id',
    'audit.column.entityClass',
    'audit.column.entityId',
    'audit.column.operation',
    'audit.column.performedBy',
    'audit.column.performedAt',
    'audit.column.snapshotBefore',
    'audit.column.snapshotAfter',
    'audit.column.changes',
    'audit.modal.title',
    'audit.button.viewAudit',
    'audit.operation.CREATE',
    'audit.operation.UPDATE',
    'audit.operation.DELETE',
    'audit.operation.UPDATE_PERMISSIONS',
    'audit.snapshot.show',
    'audit.snapshot.hide',
  ]

  /** Resolve a dot-notation key in a nested object */
  function resolveKey(obj: Record<string, unknown>, key: string): unknown {
    return key.split('.').reduce<unknown>((acc, part) => {
      if (acc && typeof acc === 'object' && part in (acc as Record<string, unknown>)) {
        return (acc as Record<string, unknown>)[part]
      }
      return undefined
    }, obj)
  }

  describe('pl.json contains all audit i18n keys', async () => {
    const plJson = (await import('@/locales/pl.json')).default as Record<string, unknown>

    it.each(requiredKeys)('key "%s" exists in pl.json', (key) => {
      const value = resolveKey(plJson, key)
      expect(value, `Missing key "${key}" in pl.json`).toBeDefined()
      expect(typeof value).toBe('string')
      expect((value as string).length).toBeGreaterThan(0)
    })
  })

  describe('ru.json contains all audit i18n keys', async () => {
    const ruJson = (await import('@/locales/ru.json')).default as Record<string, unknown>

    it.each(requiredKeys)('key "%s" exists in ru.json', (key) => {
      const value = resolveKey(ruJson, key)
      expect(value, `Missing key "${key}" in ru.json`).toBeDefined()
      expect(typeof value).toBe('string')
      expect((value as string).length).toBeGreaterThan(0)
    })
  })
})
