// Task 12.1: Component tests for DataTable rendering
// Requirements: 1.2, 12.1, 12.2, 12.4, 13.1
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ColumnConfig, PaginatedResponse } from '../types'

// Mock useBreakpoint — default desktop
let mockBreakpoint = 'desktop'
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockBreakpoint,
}))

// Mock react-i18next — keep the real module (i18n.ts wires initReactI18next,
// pulled in transitively via usePermission → Auth_Store → api-client → i18n)
// but override useTranslation so `t` returns the key verbatim for assertions.
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

// Import after mocks
import { DataTable } from '../DataTable'

interface TestRow {
  id: number
  name: string
  code: string
}

const testColumns: ColumnConfig<TestRow>[] = [
  { field: 'id', headerKey: 'columns.id', dataType: 'number', sortable: true, filterable: true },
  { field: 'name', headerKey: 'columns.name', dataType: 'string', sortable: true, filterable: true },
  { field: 'code', headerKey: 'columns.code', dataType: 'string', sortable: false, filterable: false },
]

const emptyResponse: PaginatedResponse<TestRow> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 25,
  first: true,
  last: true,
}

const filledResponse: PaginatedResponse<TestRow> = {
  content: [
    { id: 1, name: 'Admin', code: 'ADM' },
    { id: 2, name: 'User', code: 'USR' },
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

describe('DataTable rendering', () => {
  beforeEach(() => {
    mockBreakpoint = 'desktop'
    localStorage.clear()
  })

  it('renders correct number of columns from config', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="test-entity"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Wait for data to load
    const cells = await screen.findAllByRole('columnheader')
    expect(cells.length).toBe(testColumns.length)
  })

  it('skeleton renders during loading state', () => {
    // fetchFn returns a never-resolving promise to keep loading
    const fetchFn = vi.fn().mockReturnValue(new Promise(() => {}))
    renderWithProviders(
      <DataTable
        entityKey="test-skeleton"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Skeleton rows should be visible (5 rows by default)
    const skeletonRows = document.querySelectorAll('tbody tr')
    expect(skeletonRows.length).toBe(5)
  })

  it('empty state shown when content array is empty', async () => {
    const fetchFn = vi.fn().mockResolvedValue(emptyResponse)
    renderWithProviders(
      <DataTable
        entityKey="test-empty"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Wait for empty state message
    const emptyMessage = await screen.findByText('dataTable.empty.filtered')
    expect(emptyMessage).toBeInTheDocument()
  })

  it('error state with retry button on fetch failure', async () => {
    const fetchFn = vi.fn().mockRejectedValue(new Error('Network error'))
    renderWithProviders(
      <DataTable
        entityKey="test-error"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Wait for error state with retry button
    const retryButton = await screen.findByText('dataTable.error.retry')
    expect(retryButton).toBeInTheDocument()
  })

  it('mobile card layout renders when breakpoint = mobile', async () => {
    mockBreakpoint = 'mobile'
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable
        entityKey="test-mobile"
        columns={testColumns}
        fetchFn={fetchFn}
      />,
    )

    // Wait for data - on mobile, cards are rendered (no <table> element)
    await screen.findByText('Admin')
    // There should be no table element on mobile
    const table = document.querySelector('table')
    expect(table).toBeNull()
  })
})
