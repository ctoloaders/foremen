// Task 12.3: Component tests for toolbar and pagination
// Requirements: 2.2, 8.2, 8.3, 8.4, 11.5, 11.6
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ColumnConfig, PaginatedResponse } from '../types'

// Mock useBreakpoint
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

// Mock react-i18next
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (opts as { defaultValue?: string })?.defaultValue || key,
    i18n: { language: 'pl' },
  }),
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

function createFilledResponse(page: number, totalPages: number): PaginatedResponse<TestRow> {
  return {
    content: [
      { id: 1, name: 'Item A' },
      { id: 2, name: 'Item B' },
    ],
    totalElements: totalPages * 25,
    totalPages,
    number: page,
    size: 25,
    first: page === 0,
    last: page === totalPages - 1,
  }
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

describe('DataTable toolbar and pagination', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('Clear_All button hidden when no filters active', async () => {
    const fetchFn = vi.fn().mockResolvedValue(createFilledResponse(0, 1))
    renderWithProviders(
      <DataTable entityKey="toolbar-no-filter" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Clear all button should not be visible when no filters active
    const clearAllButton = screen.queryByLabelText('dataTable.filters.clearAll')
    expect(clearAllButton).not.toBeInTheDocument()
  })

  it('Clear_All button visible when filters active', async () => {
    const fetchFn = vi.fn().mockResolvedValue(createFilledResponse(0, 1))
    renderWithProviders(
      <DataTable entityKey="toolbar-filter" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Apply a filter by clicking the filter icon on the "name" column
    const filterButton = screen.getByLabelText('Filter name')
    await userEvent.click(filterButton)

    // Find the filter input inside the popover portal
    const filterInput = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await userEvent.type(filterInput, 'test')
    const applyBtn = screen.getByText('Apply')
    await userEvent.click(applyBtn)

    // Clear all button should now be visible
    const clearAllButton = screen.getByLabelText('dataTable.filters.clearAll')
    expect(clearAllButton).toBeInTheDocument()
  })

  it('pagination buttons disabled at boundaries — first page', async () => {
    // On first page (page 0 of 3 total)
    const fetchFn = vi.fn().mockResolvedValue(createFilledResponse(0, 3))
    renderWithProviders(
      <DataTable entityKey="pagination-first" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Previous button should be disabled (we're on first page)
    const prevButton = screen.getByLabelText('Previous page')
    expect(prevButton).toBeDisabled()

    // Next button should be enabled
    const nextButton = screen.getByLabelText('Next page')
    expect(nextButton).not.toBeDisabled()
  })

  it('pagination buttons disabled at boundaries — last page', async () => {
    // On last page (page 2 of 3 total)
    const fetchFn = vi.fn().mockResolvedValue(createFilledResponse(2, 3))
    renderWithProviders(
      <DataTable entityKey="pagination-last" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Next button should be disabled (we're on last page)
    const nextButton = screen.getByLabelText('Next page')
    expect(nextButton).toBeDisabled()

    // Previous button should be enabled
    const prevButton = screen.getByLabelText('Previous page')
    expect(prevButton).not.toBeDisabled()
  })
})
