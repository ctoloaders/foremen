// Task 12.2: Component tests for sort and filter interactions
// Requirements: 3.1, 3.2, 3.3, 3.5, 4.2, 4.4, 4.5, 5.2, 5.6, 6.2, 6.6
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
  amount: number
}

const testColumns: ColumnConfig<TestRow>[] = [
  { field: 'id', headerKey: 'columns.id', dataType: 'number', sortable: true, filterable: true },
  { field: 'name', headerKey: 'columns.name', dataType: 'string', sortable: true, filterable: true },
  { field: 'amount', headerKey: 'columns.amount', dataType: 'number', sortable: true, filterable: true },
]

const filledResponse: PaginatedResponse<TestRow> = {
  content: [
    { id: 1, name: 'Item A', amount: 100 },
    { id: 2, name: 'Item B', amount: 200 },
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

describe('DataTable sort and filter interactions', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('sort indicator changes on header click', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="sort-test" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Click the "name" column header to sort ascending
    const nameHeader = screen.getByText('columns.name')
    await userEvent.click(nameHeader)

    // After first click: ascending arrow should appear (ArrowUp has a class)
    // The sort indicator is rendered within the button - check for svg
    const headerContainer = nameHeader.closest('button')!
    const svgUp = headerContainer.querySelector('svg')
    expect(svgUp).not.toBeNull()
  })

  it('multi-sort priority badge displays correct numbers', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="multi-sort-test" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Click "name" column header (sort 1: name asc, priority 1)
    const nameHeader = screen.getByText('columns.name')
    await userEvent.click(nameHeader)

    // Click "amount" column header (sort 2: amount asc, priority 2)
    const amountHeader = screen.getByText('columns.amount')
    await userEvent.click(amountHeader)

    // Both should have priority badges now
    // Priority badges are small circular spans with numbers
    const badges = document.querySelectorAll('.rounded-full.bg-primary')
    expect(badges.length).toBe(2)

    const badgeTexts = Array.from(badges).map(b => b.textContent)
    expect(badgeTexts).toContain('1')
    expect(badgeTexts).toContain('2')
  })

  it('filter icon highlights when filter active', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="filter-highlight-test" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Find the filter button for the "name" column
    const filterButton = screen.getByLabelText('Filter name')

    // Initially should have muted-foreground class (not highlighted)
    expect(filterButton.className).toContain('text-muted-foreground')

    // Click filter icon to open popover, apply a filter
    await userEvent.click(filterButton)

    // Find the filter input inside the popover portal (rendered in body)
    const filterInput = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await userEvent.type(filterInput, 'test')
    const applyBtn = screen.getByText('Apply')
    await userEvent.click(applyBtn)

    // After applying filter, filter button should be highlighted (text-primary)
    const updatedFilterButton = screen.getByLabelText('Filter name')
    expect(updatedFilterButton.className).toContain('text-primary')
  })

  it('clear icon removes individual filter', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="filter-clear-test" columns={testColumns} fetchFn={fetchFn} />,
    )

    await screen.findByText('Item A')

    // Apply a filter first
    const filterButton = screen.getByLabelText('Filter name')
    await userEvent.click(filterButton)

    // Find the filter input inside the popover portal
    const filterInput = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await userEvent.type(filterInput, 'test')
    const applyBtn = screen.getByText('Apply')
    await userEvent.click(applyBtn)

    // The clear button should now be visible
    const clearButton = screen.getByLabelText('Clear filter name')
    expect(clearButton).toBeInTheDocument()

    // Click clear
    await userEvent.click(clearButton)

    // The clear button should disappear (filter removed)
    expect(screen.queryByLabelText('Clear filter name')).not.toBeInTheDocument()
  })
})
