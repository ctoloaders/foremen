import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import type { PaginatedResponse, RoleOption } from '../types'

// --- i18n mock: return the key so labels/placeholders are stable ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
  // Provided so `@/lib/i18n` (imported transitively via the shared Api_Client)
  // can call `i18n.use(initReactI18next)` under this partial mock.
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

// --- useDebounce mock: pass the value straight through (no timers) ---

vi.mock('@/hooks/useDebounce', () => ({
  useDebounce: <T,>(value: T) => value,
}))

// --- Mock the roles infinite query hook so we control the options ---
//
// The real hook (useRolesInfinite) applies a server-side `code~notin~` filter,
// and RoleSelect applies a client-side fallback filter keyed on role `code`.
// To exercise the client-side exclusion (Requirement 11.3) deterministically,
// the mock returns ADMIN and CLIENT alongside assignable roles and asserts the
// component drops them. EXCLUDED_ROLE_CODES is re-exported unchanged so the
// component's Set(EXCLUDED_CODES) still contains ADMIN/CLIENT.

const mockUseRolesInfinite = vi.fn()

vi.mock('../api/query-hooks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/query-hooks')>()
  return {
    ...actual,
    useRolesInfinite: (...args: unknown[]) => mockUseRolesInfinite(...args),
  }
})

// --- Import after mocks ---

import { RoleSelect } from '../components/RoleSelect'

// --- jsdom polyfills for Radix Popover ---

beforeAll(() => {
  if (!Element.prototype.hasPointerCapture) {
    Element.prototype.hasPointerCapture = () => false
  }
  if (!Element.prototype.setPointerCapture) {
    Element.prototype.setPointerCapture = () => {}
  }
  if (!Element.prototype.releasePointerCapture) {
    Element.prototype.releasePointerCapture = () => {}
  }
  if (!Element.prototype.scrollIntoView) {
    Element.prototype.scrollIntoView = () => {}
  }
})

// --- Helpers ---

function makePage(roles: RoleOption[]): PaginatedResponse<RoleOption> {
  return {
    content: roles,
    totalElements: roles.length,
    totalPages: 1,
    number: 0,
    size: 20,
    first: true,
    last: true,
  }
}

/** Mock useRolesInfinite return value with a single page of the given roles. */
function mockRolesPage(roles: RoleOption[]) {
  mockUseRolesInfinite.mockReturnValue({
    data: { pages: [makePage(roles)], pageParams: [0] },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
    fetchNextPage: vi.fn(),
    hasNextPage: false,
    isFetchingNextPage: false,
  })
}

// A superset that includes the two excluded roles plus assignable ones.
const ALL_ROLES: RoleOption[] = [
  { id: 1, name: 'Administrator', code: 'ADMIN' },
  { id: 2, name: 'Manager', code: 'MANAGER' },
  { id: 3, name: 'Foreman', code: 'FOREMAN' },
  { id: 4, name: 'Client', code: 'CLIENT' },
]

/** Open the popover and return the list container that holds the options. */
async function openDropdown(user: ReturnType<typeof userEvent.setup>) {
  const trigger = screen.getByRole('combobox', { name: 'users.form.role' })
  await user.click(trigger)
  // The options list renders inside the popover content (a portal).
  await waitFor(() => {
    expect(screen.getByPlaceholderText('users.form.roleSearch')).toBeInTheDocument()
  })
}

// --- Tests ---

describe('RoleSelect — ADMIN/CLIENT exclusion (Requirement 11)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRolesPage(ALL_ROLES)
  })

  describe('Create form (Requirement 11.1)', () => {
    it('excludes ADMIN and CLIENT from the dropdown options', async () => {
      const user = userEvent.setup()
      render(<RoleSelect value={undefined} onChange={vi.fn()} />)

      await openDropdown(user)

      // Assignable roles are offered as selectable option buttons.
      expect(screen.getByRole('button', { name: 'Manager' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Foreman' })).toBeInTheDocument()

      // ADMIN and CLIENT are filtered out entirely — no selectable option.
      expect(screen.queryByRole('button', { name: 'Administrator' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Client' })).not.toBeInTheDocument()
    })

    it('offers only Assignable_Roles as options', async () => {
      const user = userEvent.setup()
      render(<RoleSelect value={undefined} onChange={vi.fn()} />)

      await openDropdown(user)

      // Exactly the two assignable roles are present as option buttons.
      const options = screen
        .getAllByRole('button')
        .filter((el) => el.textContent === 'Manager' || el.textContent === 'Foreman')
      expect(options).toHaveLength(2)
    })
  })

  describe('Edit form (Requirement 11.2)', () => {
    it('excludes ADMIN and CLIENT from the dropdown options in edit mode', async () => {
      const user = userEvent.setup()
      // Editing a MANAGER user (an assignable role) — value present in options.
      render(
        <RoleSelect value={2} onChange={vi.fn()} currentRoleName="Manager" />,
      )

      await openDropdown(user)

      expect(screen.queryByRole('button', { name: 'Administrator' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Client' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Foreman' })).toBeInTheDocument()
    })
  })

  describe('Editing a CLIENT/ADMIN user shows current role as context only (Requirement 11.4)', () => {
    it('shows the current ADMIN role name on the trigger but not as a selectable option', async () => {
      const user = userEvent.setup()
      // Editing an ADMIN user: value=1 is not in the filtered options, so the
      // trigger must fall back to currentRoleName for context.
      render(
        <RoleSelect value={1} onChange={vi.fn()} currentRoleName="Administrator" />,
      )

      // Trigger displays the current role for context (11.4).
      const trigger = screen.getByRole('combobox', { name: 'users.form.role' })
      expect(trigger).toHaveTextContent('Administrator')

      // But ADMIN is not offered as a selectable option in the list.
      await openDropdown(user)
      expect(screen.queryByRole('button', { name: 'Administrator' })).not.toBeInTheDocument()
    })

    it('shows the current CLIENT role name on the trigger but not as a selectable option', async () => {
      const user = userEvent.setup()
      render(
        <RoleSelect value={4} onChange={vi.fn()} currentRoleName="Client" />,
      )

      const trigger = screen.getByRole('combobox', { name: 'users.form.role' })
      expect(trigger).toHaveTextContent('Client')

      await openDropdown(user)
      expect(screen.queryByRole('button', { name: 'Client' })).not.toBeInTheDocument()
    })

    it('does not invoke onChange for an excluded role because it is not selectable', async () => {
      const user = userEvent.setup()
      const onChange = vi.fn()
      render(<RoleSelect value={undefined} onChange={onChange} />)

      await openDropdown(user)

      // Only assignable options can be clicked; ADMIN/CLIENT have no button.
      await user.click(screen.getByRole('button', { name: 'Manager' }))
      expect(onChange).toHaveBeenCalledWith(2)
      expect(onChange).not.toHaveBeenCalledWith(1)
      expect(onChange).not.toHaveBeenCalledWith(4)
    })
  })
})
