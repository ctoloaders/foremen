/**
 * Bug Condition Exploration Tests — Frontend Rendering & Architecture Bugs
 * Spec: FOR-02-07-users-ui-fixes
 * Task 2 (Phase 1): Surface counterexamples that demonstrate BUG 1.1, 1.2, 1.3, 1.5.
 *
 * Property 1: Bug Condition — Frontend Rendering & Architecture Bugs
 *
 * CRITICAL: These tests are EXPECTED TO FAIL on UNFIXED code. Failure confirms
 * the bugs exist. They encode the desired (fixed) behavior and will pass once the
 * corresponding fixes (tasks 5, 6, 7, 9) are applied.
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.5
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'

// --- i18n mock: return the key (or a stable label) so DOM assertions are deterministic ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'pl' },
  }),
}))

// --- Capture the className that RoleSelect passes to PopoverContent (BUG 1.1) ---
// The shared ui/popover component always injects `bg-popover` via its own default
// className, which would mask the bug. By capturing the className that RoleSelect
// itself supplies, we isolate the component-level class the fix must add.
const popoverContentClassNames: string[] = []

vi.mock('@/components/ui/popover', () => {
  return {
    Popover: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    PopoverTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    PopoverContent: ({
      children,
      className,
    }: {
      children: React.ReactNode
      className?: string
    }) => {
      popoverContentClassNames.push(className ?? '')
      return (
        <div data-testid="role-popover-content" className={className}>
          {children}
        </div>
      )
    },
  }
})

import { RoleSelect } from '../components/RoleSelect'
import { UserFormSheet } from '../components/UserFormSheet'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
    },
  })
}

function renderWithProviders(ui: ReactElement) {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

/**
 * Build a paginated /api/roles response for a given page.
 * Total of `totalPages` pages, `size` roles each.
 */
function pageResponse(page: number, size: number, totalPages: number) {
  const totalElements = totalPages * size
  const content = Array.from({ length: size }, (_, i) => {
    const id = page * size + i + 1
    return { id, name: `Role ${id}` }
  })
  return {
    content,
    totalElements,
    totalPages,
    number: page,
    size,
    first: page === 0,
    last: page >= totalPages - 1,
  }
}

/**
 * Install a fetch spy over /api/roles returning `totalPages` pages.
 * Returns the spy so tests can inspect call count / URLs.
 */
function installRolesFetch(totalPages: number, sizePerPage: number) {
  const spy = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString()
    const parsed = new URL(url, 'http://localhost')
    const page = Number(parsed.searchParams.get('page') ?? '0')
    const size = Number(parsed.searchParams.get('size') ?? String(sizePerPage))
    const body = pageResponse(page, size, totalPages)
    return {
      ok: true,
      status: 200,
      json: async () => body,
    } as unknown as Response
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

// ---------------------------------------------------------------------------

describe('BUG condition exploration — frontend (Property 1)', () => {
  beforeEach(() => {
    popoverContentClassNames.length = 0
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  // -------------------------------------------------------------------------
  // BUG 1.1 — Dark theme popover background
  // The RoleSelect PopoverContent must explicitly carry `bg-popover`.
  // UNFIXED: className is "w-[var(--radix-popover-trigger-width)] p-0" (no bg).
  // EXPECTED TO FAIL until task 5 adds `bg-popover`.
  // -------------------------------------------------------------------------
  it('BUG 1.1: RoleSelect PopoverContent has bg-popover class', () => {
    installRolesFetch(1, 20)

    renderWithProviders(<RoleSelect value={undefined} onChange={() => {}} />)

    // RoleSelect renders exactly one PopoverContent; capture its className.
    expect(popoverContentClassNames.length).toBeGreaterThan(0)
    const className = popoverContentClassNames[0]

    // Counterexample on unfixed code: className lacks `bg-popover`.
    expect(className).toContain('bg-popover')
  })

  // -------------------------------------------------------------------------
  // BUG 1.2 — Opening RoleSelect must trigger exactly ONE fetch (page=0, size=20)
  // UNFIXED: fetchRolesForSelect() loops over ALL pages with size=100.
  // EXPECTED TO FAIL until task 6 introduces paginated fetch.
  // -------------------------------------------------------------------------
  it('BUG 1.2: opening RoleSelect triggers exactly 1 fetch (page=0, size=20)', async () => {
    // 3 pages available — unfixed code will fetch all 3 (one per page).
    const spy = installRolesFetch(3, 100)

    renderWithProviders(<RoleSelect value={undefined} onChange={() => {}} />)

    // Wait for the initial role fetch(es) to settle.
    await waitFor(() => {
      expect(spy).toHaveBeenCalled()
    })

    // Give any fetch-all loop a chance to fire all its calls.
    await new Promise((r) => setTimeout(r, 50))

    // Exactly one request should have been made.
    expect(spy).toHaveBeenCalledTimes(1)

    // And it should target page=0 with size=20.
    const firstCall = spy.mock.calls[0]
    expect(firstCall).toBeTruthy()
    const firstCallUrl = String(firstCall![0])
    const parsed = new URL(firstCallUrl, 'http://localhost')
    expect(parsed.searchParams.get('page')).toBe('0')
    expect(parsed.searchParams.get('size')).toBe('20')
  })

  // -------------------------------------------------------------------------
  // BUG 1.3 — Typing in the search input must trigger a server-side request
  // to /api/roles?query=name~ct~{input}.
  // UNFIXED: search only filters the already-fetched client-side array; no
  // additional fetch is made.
  // EXPECTED TO FAIL until task 7 adds debounced server-side search.
  // -------------------------------------------------------------------------
  it('BUG 1.3: typing in search triggers fetch with query=name~ct~{input}', async () => {
    const spy = installRolesFetch(1, 20)

    renderWithProviders(<RoleSelect value={undefined} onChange={() => {}} />)

    // Wait for the initial (unfiltered) fetch to complete.
    await waitFor(() => {
      expect(spy).toHaveBeenCalled()
    })
    const callsBeforeSearch = spy.mock.calls.length

    // Type a query into the role search input.
    const searchInput = screen.getByPlaceholderText('users.form.roleSearch')
    fireEvent.change(searchInput, { target: { value: 'manager' } })

    // Wait past the debounce window (300ms) for a server request to fire.
    await waitFor(
      () => {
        const searchCall = spy.mock.calls.find((call) => {
          const url = String(call[0])
          return url.includes('query=') && url.includes('name~ct~manager')
        })
        expect(searchCall).toBeTruthy()
      },
      { timeout: 1500 },
    )

    // A NEW fetch (beyond the initial one) must have occurred.
    expect(spy.mock.calls.length).toBeGreaterThan(callsBeforeSearch)
  })

  // -------------------------------------------------------------------------
  // BUG 1.5 — UserFormSheet locale Select must NOT render an "en" option.
  // UNFIXED: <SelectItem value="en">English (EN)</SelectItem> is present.
  // EXPECTED TO FAIL until task 9 removes the English option.
  // -------------------------------------------------------------------------
  it('BUG 1.5: UserFormSheet does not render a locale option with value "en"', () => {
    installRolesFetch(1, 20)

    renderWithProviders(
      <UserFormSheet
        open={true}
        mode="create"
        userId={null}
        onClose={() => {}}
        onSuccess={() => {}}
      />,
    )

    // Radix Select renders SelectItems into a hidden native <select> for a11y;
    // an <option value="en"> (or any element carrying the "en" value) indicates
    // the English locale is still selectable.
    const englishOptions = document.querySelectorAll('option[value="en"]')
    const englishByText = screen.queryByText('English (EN)')

    // Counterexample on unfixed code: an English (EN) locale option exists.
    expect(englishOptions).toHaveLength(0)
    expect(englishByText).toBeNull()
  })
})
