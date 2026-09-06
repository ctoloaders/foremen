// Task 8.2: Component tests for AuditModal
// Requirements: 6.1, 6.2, 6.3, 6.5
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'ru' },
  }),
  // Provided so `@/lib/i18n` (imported transitively via the shared Api_Client)
  // can call `i18n.use(initReactI18next)` under this partial mock.
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

import { AuditModal } from '../AuditModal'

const mockAuditRecords = [
  {
    id: 1,
    entityClass: 'RoleEntity',
    entityId: 5,
    operation: 'CREATE',
    performedBy: 'admin',
    performedAt: '2024-01-01T10:00:00',
    snapshotBefore: null,
    snapshotAfter: { name: 'Admin' },
  },
  {
    id: 2,
    entityClass: 'RoleEntity',
    entityId: 5,
    operation: 'UPDATE',
    performedBy: 'admin',
    performedAt: '2024-01-02T12:00:00',
    snapshotBefore: { name: 'Admin' },
    snapshotAfter: { name: 'Super Admin' },
  },
]

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

describe('AuditModal', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockAuditRecords),
    }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders Dialog when open=true', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <AuditModal open={true} onClose={onClose} entityKey="roles" entityId={5} />,
    )

    // Dialog should be visible with its title
    const title = await screen.findByText(/audit\.modal\.title/)
    expect(title).toBeInTheDocument()
  })

  it('does not render Dialog content when open=false', () => {
    const onClose = vi.fn()
    renderWithProviders(
      <AuditModal open={false} onClose={onClose} entityKey="roles" entityId={5} />,
    )

    // Dialog title should not be in the document
    expect(screen.queryByText(/audit\.modal\.title/)).not.toBeInTheDocument()
  })

  it('title includes entityKey and entityId', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <AuditModal open={true} onClose={onClose} entityKey="roles" entityId={5} />,
    )

    // Title should contain entityKey and entityId formatted as "audit.modal.title — roles #5"
    const title = await screen.findByText(/roles #5/)
    expect(title).toBeInTheDocument()
  })

  it('renders a Table (not full DataTable) with audit records', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <AuditModal open={true} onClose={onClose} entityKey="roles" entityId={5} />,
    )

    // Wait for data to be fetched and table to render
    await waitFor(() => {
      const table = document.querySelector('table')
      expect(table).not.toBeNull()
    })

    // Should display column headers from auditModalColumns (omitting entityClass and entityId)
    expect(screen.getByText('audit.column.id')).toBeInTheDocument()
    expect(screen.getByText('audit.column.operation')).toBeInTheDocument()
    expect(screen.getByText('audit.column.performedBy')).toBeInTheDocument()
    expect(screen.getByText('audit.column.performedAt')).toBeInTheDocument()

    // entityClass and entityId columns should NOT be present
    expect(screen.queryByText('audit.column.entityClass')).not.toBeInTheDocument()
    expect(screen.queryByText('audit.column.entityId')).not.toBeInTheDocument()
  })

  it('calls onClose when Dialog is dismissed via Escape key', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(
      <AuditModal open={true} onClose={onClose} entityKey="roles" entityId={5} />,
    )

    // Wait for dialog to appear
    await screen.findByText(/audit\.modal\.title/)

    // Press Escape to dismiss the dialog
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalled()
  })
})
