/**
 * Preservation Property Tests — Frontend Valid Behavior Unchanged
 * Spec: FOR-02-07-users-ui-fixes
 * Task 4 (Phase 2): Capture baseline behavior that must NOT regress after the fixes.
 *
 * Property 2: Preservation — Frontend Valid Behavior Unchanged (Property 8 in design)
 *
 * Methodology: observation-first. Every assertion here describes behavior that is
 * already TRUE on the UNFIXED code AND must remain TRUE after the fixes (tasks 5-11)
 * are applied. None of these assertions depend on the buggy behavior:
 *   - They never assert the ABSENCE of `bg-popover` (the fix ADDS it).
 *   - They target only valid locale options ("ru" / "pl"), never "en".
 *   - They exercise open/select/close and search-reset flows that are unchanged by design.
 *
 * Validates: Requirements 3.1, 3.2, 3.6, 3.7
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'

import { userFormSchema } from '../schemas/user-schema'

// --- i18n mock: return the key so DOM assertions are deterministic ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'pl', changeLanguage: vi.fn() },
  }),
  // Provided so `@/lib/i18n` (imported transitively via the shared Api_Client)
  // can call `i18n.use(initReactI18next)` under this partial mock.
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- Mock the shared shadcn Select as native <select>/<option> for testability.
// This mirrors the approach used by UserFormSheet.test.tsx so the locale options
// render as inspectable DOM nodes without Radix portal/pointer complications.
vi.mock('@/components/ui/select', () => ({
  Select: ({
    children,
    value,
    onValueChange,
    disabled,
  }: {
    children: React.ReactNode
    value?: string
    onValueChange?: (val: string) => void
    disabled?: boolean
  }) => (
    <select
      data-testid="locale-select"
      value={value ?? ''}
      onChange={(e) => onValueChange?.(e.target.value)}
      disabled={disabled}
    >
      {children}
    </select>
  ),
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectValue: ({ placeholder }: { placeholder?: string }) => (
    <option value="">{placeholder}</option>
  ),
  SelectContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectItem: ({ children, value }: { children: React.ReactNode; value: string }) => (
    <option value={value}>{children}</option>
  ),
}))

// --- Mock the shared ui/popover so PopoverContent always renders its children.
// The real Radix Popover mounts content in a portal only while open and relies on
// pointer-capture APIs that jsdom does not implement, which makes open/close flows
// flaky. Rendering content unconditionally lets us exercise the search + selection
// behavior directly. `open`/`onOpenChange` are still wired so we can observe the
// component driving the popover open state (used by the reset-on-reopen test).
let popoverOpen = false
let popoverOnOpenChange: ((open: boolean) => void) | undefined

vi.mock('@/components/ui/popover', () => ({
  Popover: ({
    children,
    open,
    onOpenChange,
  }: {
    children: React.ReactNode
    open?: boolean
    onOpenChange?: (open: boolean) => void
  }) => {
    popoverOpen = open ?? false
    popoverOnOpenChange = onOpenChange
    return <div data-testid="role-popover">{children}</div>
  },
  PopoverTrigger: ({ children }: { children: React.ReactNode; asChild?: boolean }) => (
    <div data-testid="role-popover-trigger">{children}</div>
  ),
  PopoverContent: ({
    children,
    className,
  }: {
    children: React.ReactNode
    className?: string
  }) => (
    <div data-testid="role-popover-content" className={className}>
      {children}
    </div>
  ),
}))

// Mock PhoneInput to keep UserFormSheet rendering simple.
vi.mock('../components/PhoneInput', () => ({
  PhoneInput: ({
    value,
    onChange,
    disabled,
  }: {
    value: string
    onChange: (val: string) => void
    error?: string
    disabled?: boolean
  }) => (
    <input
      data-testid="phone-input"
      type="text"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      aria-label="phone"
    />
  ),
}))

vi.mock('../components/UserFormSkeleton', () => ({
  UserFormSkeleton: () => <div data-testid="user-form-skeleton" />,
}))

vi.mock('../api/mutation-hooks', () => ({
  useCreateUser: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateUser: () => ({ mutate: vi.fn(), isPending: false }),
  isEmailConflictError: (error: unknown) =>
    (error as { status?: number })?.status === 409,
}))

import { RoleSelect } from '../components/RoleSelect'
import { UserFormSheet } from '../components/UserFormSheet'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
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
 * Works for both the current fetch-all-pages implementation and the future
 * infinite-scroll implementation, since both read from `fetch`.
 */
function pageResponse(
  page: number,
  size: number,
  totalPages: number,
  names?: string[],
) {
  const totalElements = totalPages * size
  const content = Array.from({ length: size }, (_, i) => {
    const id = page * size + i + 1
    const name = names?.[i] ?? `Role ${id}`
    return { id, name }
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
 * Install a fetch spy over /api/roles. A single page of the given roles is
 * returned regardless of the requested size, which keeps the response valid
 * for both the paginated and fetch-all strategies.
 */
function installRolesFetch(names: string[]) {
  const spy = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString()
    const parsed = new URL(url, 'http://localhost')
    const page = Number(parsed.searchParams.get('page') ?? '0')
    const size = Number(parsed.searchParams.get('size') ?? String(names.length))
    // Single page containing all provided roles.
    const body =
      page === 0
        ? pageResponse(0, names.length, 1, names)
        : pageResponse(page, size, 1, [])
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

describe('Preservation — frontend valid behavior unchanged (Property 2 / Property 8)', () => {
  beforeEach(() => {
    localStorage.clear()
    popoverOpen = false
    popoverOnOpenChange = undefined
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  // -------------------------------------------------------------------------
  // Requirement 3.1 — Light theme rendering of RoleSelect is correct.
  // We assert the popover renders its search input and roles list structure.
  // We do NOT assert the ABSENCE of `bg-popover`, so the BUG 1.1 fix (which ADDS
  // that class) does not regress this test. The structure below is theme-agnostic
  // and holds on both unfixed and fixed code.
  // -------------------------------------------------------------------------
  it('3.1: RoleSelect renders its list content correctly (light theme, no regression)', async () => {
    installRolesFetch(['Manager', 'Client'])

    // Ensure light theme: no `dark` class on the document element.
    document.documentElement.classList.remove('dark')

    renderWithProviders(<RoleSelect value={undefined} onChange={() => {}} />)

    // Search input renders (present in both implementations).
    const searchInput = await screen.findByPlaceholderText('users.form.roleSearch')
    expect(searchInput).toBeInTheDocument()

    // Roles load and render as selectable options.
    await waitFor(() => {
      expect(screen.getByText('Manager')).toBeInTheDocument()
      expect(screen.getByText('Client')).toBeInTheDocument()
    })

    // Light theme baseline: the document is not in dark mode.
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  // -------------------------------------------------------------------------
  // Requirement 3.2 (render side) — Valid locale options present.
  // "Polski (PL)" and "Русский (RU)" must remain in the locale Select.
  // These stay after the BUG 1.5 fix (which only removes the "en" option).
  // -------------------------------------------------------------------------
  it('3.2: UserFormSheet renders "Polski (PL)" and "Русский (RU)" locale options', async () => {
    installRolesFetch(['Manager'])

    renderWithProviders(
      <UserFormSheet
        open={true}
        mode="create"
        userId={null}
        onClose={() => {}}
        onSuccess={() => {}}
      />,
    )

    // Both valid locale options must be present and selectable.
    const localeSelect = screen.getByTestId('locale-select')
    expect(within(localeSelect).getByText('Polski (PL)')).toBeInTheDocument()
    expect(within(localeSelect).getByText('Русский (RU)')).toBeInTheDocument()

    // Their option values are the valid locales.
    const plOption = localeSelect.querySelector('option[value="pl"]')
    const ruOption = localeSelect.querySelector('option[value="ru"]')
    expect(plOption).not.toBeNull()
    expect(ruOption).not.toBeNull()
  })

  // -------------------------------------------------------------------------
  // Requirement 3.2 (schema side) — Zod schema accepts valid locales.
  // "ru" and "pl" must continue to pass. The BUG 1.5 fix only removes "en",
  // so these assertions remain valid afterwards.
  // -------------------------------------------------------------------------
  it('3.2: Zod schema accepts locale "ru" and "pl"', () => {
    const base = {
      name: 'John Doe',
      email: 'john@example.com',
      phone: '+48789736625',
      roleId: 1,
      active: true,
    }

    const ru = userFormSchema.safeParse({ ...base, locale: 'ru' })
    expect(ru.success).toBe(true)

    const pl = userFormSchema.safeParse({ ...base, locale: 'pl' })
    expect(pl.success).toBe(true)
  })

  // -------------------------------------------------------------------------
  // Requirement 3.2 / general — RoleSelect open -> select -> close -> value updates.
  // The mouse interaction pattern (choose a role, popover closes, onChange fires
  // with the chosen id) must remain unchanged by design across the fixes.
  // -------------------------------------------------------------------------
  it('3.2: RoleSelect interaction open -> select -> close -> value updates', async () => {
    installRolesFetch(['Manager', 'Client'])

    const handleChange = vi.fn()

    renderWithProviders(<RoleSelect value={undefined} onChange={handleChange} />)

    // Roles are loaded and the option is present (dropdown content is available).
    const managerOption = await screen.findByText('Manager')
    expect(managerOption).toBeInTheDocument()

    // Selecting a role invokes onChange with the role id and closes the list.
    fireEvent.click(managerOption)

    await waitFor(() => {
      expect(handleChange).toHaveBeenCalledTimes(1)
    })
    // "Manager" is id 1 in the fetch stub (page 0, index 0).
    expect(handleChange).toHaveBeenCalledWith(1)
  })

  // -------------------------------------------------------------------------
  // Requirement 3.7 — RoleSelect search state resets on close/reopen.
  // After typing a query and re-opening the dropdown, the search input must be
  // empty and the full (unfiltered) first page must be shown again.
  // -------------------------------------------------------------------------
  it('3.7: RoleSelect resets search state on close and reopen', async () => {
    installRolesFetch(['Manager', 'Client'])

    const handleChange = vi.fn()

    renderWithProviders(<RoleSelect value={undefined} onChange={handleChange} />)

    // Wait for roles + search input.
    const searchInput = (await screen.findByPlaceholderText(
      'users.form.roleSearch',
    )) as HTMLInputElement
    await screen.findByText('Manager')

    // Type a query — the input reflects the typed value.
    fireEvent.change(searchInput, { target: { value: 'Manager' } })
    expect(searchInput.value).toBe('Manager')

    // Select a role — this closes the dropdown and, by design, resets search.
    fireEvent.click(screen.getByText('Manager'))
    await waitFor(() => {
      expect(handleChange).toHaveBeenCalledWith(1)
    })

    // Selecting a role drives the popover closed via onOpenChange(false).
    expect(popoverOpen).toBe(false)

    // Re-open the dropdown by driving the popover open state, as a user click would.
    await waitFor(() => {
      expect(popoverOnOpenChange).toBeTypeOf('function')
    })
    fireEvent.click(screen.getByRole('combobox'))
    act(() => {
      popoverOnOpenChange?.(true)
    })

    // On reopen the search input is cleared and the full first page is shown again.
    await waitFor(() => {
      const reopenedInput = screen.getByPlaceholderText(
        'users.form.roleSearch',
      ) as HTMLInputElement
      expect(reopenedInput.value).toBe('')
    })

    await waitFor(() => {
      expect(screen.getByText('Manager')).toBeInTheDocument()
      expect(screen.getByText('Client')).toBeInTheDocument()
    })
  })
})
