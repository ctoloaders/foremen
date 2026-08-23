// Task 8.3: Component tests for DataTable audit button integration
// Requirements: 5.1, 5.2, 5.3, 5.4
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ColumnConfig, PaginatedResponse } from '../types'

// Mock useBreakpoint — desktop
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (opts as { defaultValue?: string })?.defaultValue || key,
    i18n: { language: 'ru' },
  }),
}))

// Mock AuditModal to capture its props
const mockAuditModal = vi.fn((_props: Record<string, unknown>) => null)
vi.mock('../AuditModal', () => ({
  AuditModal: (props: Record<string, unknown>) => {
    mockAuditModal(props)
    return props.open ? <div data-testid="audit-modal">Audit Modal: {props.entityKey as string} #{props.entityId as number}</div> : null
  },
}))

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
    { id: 1, name: 'Admin' },
    { id: 2, name: 'User' },
  ],
  totalElements: 2,
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

describe('DataTable audit button integration', () => {
  beforeEach(() => {
    localStorage.clear()
    mockAuditModal.mockClear()
  })

  it('renders audit button by default (showAuditButton undefined)', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="roles"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Wait for data to load
    await screen.findByText('Admin')

    // Audit buttons should be rendered (one per row)
    const auditButtons = screen.getAllByLabelText('audit.button.viewAudit')
    expect(auditButtons.length).toBe(2)
  })

  it('renders audit button when showAuditButton=true', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="roles"
        columns={testColumns}
        fetchFn={fetchFn}
        showAuditButton={true}
      />,
    )

    await screen.findByText('Admin')

    const auditButtons = screen.getAllByLabelText('audit.button.viewAudit')
    expect(auditButtons.length).toBe(2)
  })

  it('does NOT render audit button when showAuditButton=false', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="audit"
        columns={testColumns}
        fetchFn={fetchFn}
        showAuditButton={false}
      />,
    )

    await screen.findByText('Admin')

    const auditButtons = screen.queryAllByLabelText('audit.button.viewAudit')
    expect(auditButtons.length).toBe(0)
  })

  it('click on audit button opens AuditModal with correct entityKey and entityId', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="roles"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    await screen.findByText('Admin')

    // Click the audit button on the first row (id: 1)
    const auditButtons = screen.getAllByLabelText('audit.button.viewAudit')
    await user.click(auditButtons[0]!)

    // AuditModal should now be rendered with correct props
    await waitFor(() => {
      expect(screen.getByTestId('audit-modal')).toBeInTheDocument()
    })

    // Verify the modal was called with correct entityKey and entityId
    expect(mockAuditModal).toHaveBeenCalledWith(
      expect.objectContaining({
        open: true,
        entityKey: 'roles',
        entityId: 1,
      }),
    )
  })
})
