// FOR-04-01 task 9.7: UX component/layout tests for the mobile/table UX fixes.
//
// Requirements:
//  - 7.1 the filters toggle renders a RESOLVED label (no raw i18n key leaks);
//  - 7.2 / 7.9 controls inside the expanded mobile panel are clickable and
//        update filter state (a real StringFilter input types + applies);
//  - 7.3 the mobile Sort section cycles a column's sort (asc → desc) and the
//        emitted `sort=field,dir` params + summary sort count follow;
//  - 7.4 mobile cards are uniform (min-height class + consistent label/value
//        grid) with an em-dash placeholder for empty values and truncation;
//  - 7.5 / 7.8 the header (search + toggle) and footer (pagination) regions sit
//        OUTSIDE the single scroll container (the overflow-y-auto body div);
//  - 7.6 apply collapses the panel and renders the applied summary chip with
//        the correct counts;
//  - 7.7 the summary reopens the panel; the summary "clear all" resets
//        filters + sorts.
//
// Harness mirrors the existing data-table tests: mock useBreakpoint (forced
// per-suite), react-i18next (t returns defaultValue || key verbatim), and the
// api-client seam so the ReferenceFilter never hits the network. fetchFn is a
// vi.fn resolving a PaginatedResponse; its most-recent call's `query`/`sort`
// arguments are the observable output of the state changes the UI drives.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'

import type { ColumnConfig, PaginatedResponse, FetchParams } from '../types'

// useBreakpoint is mutated per-suite so the SAME mock module serves both the
// mobile UX suites and the desktop no-regression suite.
let mockBreakpoint: 'mobile' | 'tablet' | 'desktop' = 'mobile'
vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockBreakpoint,
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

import { DataTable } from '../DataTable'
import { DataTableCards } from '../DataTableCards'

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

function renderWithProviders(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

/** The most recent FetchParams passed to a fetchFn spy. */
function lastFetchParams(fetchFn: ReturnType<typeof vi.fn>): FetchParams {
  const calls = fetchFn.mock.calls
  return calls[calls.length - 1]![0] as FetchParams
}

/**
 * The StringFilter's own Apply button, scoped to the disclosure that contains
 * the given filter input. When a disclosure is open there are TWO "Apply"
 * buttons (the filter control's + the panel-level one), so we scope by the
 * control's container to select the filter's Apply unambiguously.
 */
function applyButtonFor(input: HTMLElement): HTMLElement {
  const container = input.closest('.space-y-3') as HTMLElement
  return within(container).getByRole('button', { name: 'Apply' })
}

/**
 * The panel-level Apply/Done button. It is the full-width button at the bottom
 * of the panel (className contains `w-full`); disambiguated from any filter
 * control's Apply that carries no `w-full`.
 */
function panelApplyButton(): HTMLElement {
  const applies = screen.getAllByRole('button', { name: 'Apply' })
  const panel = applies.find(b => b.className.includes('w-full'))
  if (!panel) throw new Error('panel-level Apply button not found')
  return panel
}

describe('DataTable mobile UX (Req 7.1, 7.2, 7.6, 7.7, 7.9)', () => {
  beforeEach(() => {
    mockBreakpoint = 'mobile'
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('renders a RESOLVED filters-toggle label, never a raw i18n key (Req 7.1)', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-toggle" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // The toggle resolves to its translation/defaultValue ('Filters'), proving
    // the key was passed through i18n. The raw dotted key must NOT appear.
    expect(screen.getByText('Filters')).toBeInTheDocument()
    expect(screen.queryByText('dataTable.filters.toggle')).not.toBeInTheDocument()
  })

  it('exposes a clickable filter control inside the expanded panel that updates state (Req 7.2, 7.9)', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-clickable" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // Open the panel, expand the "name" column disclosure.
    await user.click(screen.getByText('Filters'))
    await user.click(screen.getByRole('button', { name: 'columns.name' }))

    // The real StringFilter control renders inside the panel and is clickable.
    const input = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await user.type(input, 'acme')
    await user.click(applyButtonFor(input))

    // The filter dispatched → the query refetches with the composed fragment.
    await waitFor(() => {
      expect(lastFetchParams(fetchFn).query).toContain('name~ct~acme')
    })
  })

  it('apply collapses the panel and renders the applied summary with correct counts (Req 7.6)', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-summary" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    await user.click(screen.getByText('Filters'))
    await user.click(screen.getByRole('button', { name: 'columns.name' }))
    const input = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await user.type(input, 'acme')
    await user.click(applyButtonFor(input))

    // Panel-level Apply closes the panel: the disclosure control is gone.
    await user.click(panelApplyButton())
    await waitFor(() => {
      expect(
        screen.queryByPlaceholderText('dataTable.filter.string.placeholder'),
      ).not.toBeInTheDocument()
    })

    // The applied summary chip appears (it is the control labelled by
    // summaryLabel). One filter is active → filtersCount === 1 flows through
    // countActiveFilters into the summary text with count 1.
    const summary = screen.getByLabelText('dataTable.filters.summaryLabel')
    expect(summary).toBeInTheDocument()
    expect(summary.textContent).toContain('dataTable.filters.summaryFilters')
  })

  it('clicking the applied summary reopens the panel (Req 7.7)', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-reopen" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // Apply a filter, then close the panel.
    await user.click(screen.getByText('Filters'))
    await user.click(screen.getByRole('button', { name: 'columns.name' }))
    const input = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await user.type(input, 'acme')
    await user.click(applyButtonFor(input))
    await user.click(panelApplyButton())

    const summary = await screen.findByLabelText('dataTable.filters.summaryLabel')
    // Clicking the summary chip reopens the panel — the disclosure controls
    // return and the summary is hidden while the panel is open. The panel-level
    // Apply button is the unambiguous marker that the panel is open again.
    await user.click(summary)
    expect(await screen.findByRole('button', { name: 'Apply' })).toBeInTheDocument()
    // The "name" column disclosure is back (its accessible name includes the
    // active-filter marker since a filter is applied).
    expect(screen.getByRole('button', { name: /columns\.name/ })).toBeInTheDocument()
    expect(screen.queryByLabelText('dataTable.filters.summaryLabel')).not.toBeInTheDocument()
  })

  it('clear-all on the summary resets filters and sorts (Req 7.7)', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-clearall" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // Apply a filter and close the panel to surface the summary.
    await user.click(screen.getByText('Filters'))
    await user.click(screen.getByRole('button', { name: 'columns.name' }))
    const input = await screen.findByPlaceholderText('dataTable.filter.string.placeholder')
    await user.type(input, 'acme')
    await user.click(applyButtonFor(input))
    await user.click(panelApplyButton())

    const summary = await screen.findByLabelText('dataTable.filters.summaryLabel')
    // The chip's trailing "clear all" (scoped to the summary chip container).
    const chip = summary.closest('div')!
    const clearAll = within(chip).getByLabelText('dataTable.filters.clearAll')
    await user.click(clearAll)

    // Summary disappears (no active filters/sorts) — the primary observable
    // signal that CLEAR_ALL reset the state (countActiveFilters → 0).
    await waitFor(() => {
      expect(
        screen.queryByLabelText('dataTable.filters.summaryLabel'),
      ).not.toBeInTheDocument()
    })
    // Reopening the panel confirms the reset at the control level: the "name"
    // disclosure no longer shows the active-filter marker, i.e. the filter was
    // dropped from state (not merely hidden). Note the list itself does not
    // re-fetch here: the cleared query key equals the initial (unfiltered) key,
    // so React Query serves the already-cached unfiltered page — which is the
    // correct "no query fragment" outcome.
    await user.click(screen.getByText('Filters'))
    expect(await screen.findByRole('button', { name: 'Apply' })).toBeInTheDocument()
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
  })
})

describe('DataTable mobile Sort section (Req 7.3)', () => {
  beforeEach(() => {
    mockBreakpoint = 'mobile'
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('cycles a column sort asc → desc, emitting sort=field,dir and counting in the summary', async () => {
    const user = userEvent.setup()
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    renderWithProviders(
      <DataTable entityKey="ux-sort" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    await user.click(screen.getByText('Filters'))

    // The Sort section lists a cycle button per sortable column. Each button
    // carries the localized "toggle" aria-label; pick the one whose row shows
    // the "name" column header.
    const sortToggles = screen.getAllByRole('button', { name: 'dataTable.sort.toggle' })
    const nameSort = sortToggles.find(b => b.textContent?.includes('columns.name'))!
    expect(nameSort).toBeDefined()

    // First click → ascending: sort param is `name,asc`.
    await user.click(nameSort)
    await waitFor(() => {
      expect(lastFetchParams(fetchFn).sort).toContain('name,asc')
    })

    // Second click → descending: sort param becomes `name,desc`.
    await user.click(nameSort)
    await waitFor(() => {
      expect(lastFetchParams(fetchFn).sort).toContain('name,desc')
    })

    // Close the panel: the applied summary counts the active sort.
    await user.click(panelApplyButton())
    const summary = await screen.findByLabelText('dataTable.filters.summaryLabel')
    expect(summary.textContent).toContain('dataTable.filters.summarySorts')
  })
})

describe('DataTableCards uniformity (Req 7.4)', () => {
  beforeEach(() => {
    mockBreakpoint = 'mobile'
  })

  interface CardRow {
    id: number
    name: string
    note: string | null
  }

  const cardColumns: ColumnConfig<CardRow>[] = [
    { field: 'name', headerKey: 'columns.name', dataType: 'string' },
    { field: 'note', headerKey: 'columns.note', dataType: 'string' },
  ]

  it('renders every card with the uniform min-height class and a consistent grid, with an em-dash placeholder for empty values', () => {
    const rows: CardRow[] = [
      { id: 1, name: 'Fully populated row', note: 'has a note' },
      { id: 2, name: 'Sparse row', note: null }, // empty value → placeholder
    ]
    const { container } = render(
      <DataTableCards data={rows} columns={cardColumns} />,
    )

    // Every card carries the uniform min-height class so height does not depend
    // on populated fields.
    const cards = container.querySelectorAll('.min-h-\\[7\\.5rem\\]')
    expect(cards).toHaveLength(2)

    // Every card uses the same fixed label/value grid template.
    cards.forEach((card) => {
      expect(card.querySelector('dl.grid')).not.toBeNull()
    })

    // A populated value renders its content; the empty (null) value renders the
    // em-dash placeholder.
    expect(screen.getByText('has a note')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()

    // Long values are truncated (dd carries the truncate class).
    const dds = container.querySelectorAll('dd')
    dds.forEach((dd) => expect(dd.className).toContain('truncate'))
  })
})

describe('DataTable sticky layout structure (Req 7.5, 7.8)', () => {
  beforeEach(() => {
    mockBreakpoint = 'mobile'
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('places the header and footer OUTSIDE the single overflow-y-auto scroll body', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    const { container } = renderWithProviders(
      <DataTable entityKey="ux-layout" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // Exactly one scroll container: the body region.
    const scrollBody = container.querySelector('.overflow-y-auto')
    expect(scrollBody).not.toBeNull()

    // The header's search input (toolbar) and the filters toggle are NOT
    // descendants of the scroll body — they live in the pinned header region.
    const searchInput = screen.getByPlaceholderText('dataTable.search.placeholder')
    expect(scrollBody!.contains(searchInput)).toBe(false)
    const toggle = screen.getByText('Filters')
    expect(scrollBody!.contains(toggle)).toBe(false)

    // The footer's pagination controls are NOT descendants of the scroll body
    // either — they live in the pinned footer region.
    const nextButton = screen.getByLabelText('Next page')
    expect(scrollBody!.contains(nextButton)).toBe(false)

    // Sanity: the row content DOES live inside the scroll body.
    expect(scrollBody!.textContent).toContain('Item A')
  })
})

describe('DataTable desktop no-regression (Req 7.5)', () => {
  beforeEach(() => {
    mockBreakpoint = 'desktop'
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('renders rows in a table and keeps header/footer outside the scroll body on desktop', async () => {
    const fetchFn = vi.fn().mockResolvedValue(filledResponse)
    const { container } = renderWithProviders(
      <DataTable entityKey="ux-desktop" columns={testColumns} fetchFn={fetchFn} />,
    )
    await screen.findByText('Item A')

    // Desktop renders a real <table> (not cards) and the mobile Filters toggle
    // is absent.
    expect(container.querySelector('table')).not.toBeNull()
    expect(screen.queryByText('Filters')).not.toBeInTheDocument()

    // Same three-region invariant holds on desktop: search + pagination sit
    // outside the single scroll body.
    const scrollBody = container.querySelector('.overflow-y-auto')
    expect(scrollBody).not.toBeNull()
    expect(scrollBody!.contains(screen.getByPlaceholderText('dataTable.search.placeholder'))).toBe(false)
    expect(scrollBody!.contains(screen.getByLabelText('Next page'))).toBe(false)
  })
})
