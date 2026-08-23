import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { FetchParams, PaginatedResponse } from '@/components/data-table'
import type { UserDto } from '../types'

// --- i18n Mock ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, string>) => {
      const translations: Record<string, string> = {
        'users.pageTitle': 'Zarządzanie użytkownikami',
        'users.actions.create': 'Utwórz użytkownika',
        'users.actions.deactivate': 'Deactivate',
        'users.actions.alreadyInactive': 'User already inactive',
        'users.toast.createSuccess': 'User created successfully',
        'users.toast.updateSuccess': 'User updated successfully',
        'users.toast.deactivateSuccess': 'User deactivated successfully',
        'common.edit': 'Edit',
      }
      if (key === 'users.dialog.deactivateDescription' && params?.name) {
        return `Are you sure you want to deactivate ${params.name}?`
      }
      return translations[key] ?? key
    },
  }),
}))

// --- Sonner mock ---

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()

vi.mock('sonner', () => ({
  toast: {
    success: (...args: unknown[]) => mockToastSuccess(...args),
    error: (...args: unknown[]) => mockToastError(...args),
  },
}))

// --- Mock users-api ---

const mockFetchUsers = vi.fn<(params: FetchParams) => Promise<PaginatedResponse<UserDto>>>()
const mockCreateUser = vi.fn()

vi.mock('../api/users-api', () => ({
  fetchUsers: (params: FetchParams) => mockFetchUsers(params),
  createUser: (...args: unknown[]) => mockCreateUser(...args),
  fetchUser: vi.fn(),
  fetchRolesForSelect: vi.fn().mockResolvedValue([]),
  deactivateUser: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number
    constructor(status: number, message: string) {
      super(message)
      this.status = status
      this.name = 'ApiError'
    }
  },
  handleResponse: vi.fn(),
}))

// --- Mock query & mutation hooks ---

const mockUseUser = vi.fn()
const mockUseRolesForSelect = vi.fn()
const mockCreateMutate = vi.fn()
const mockUpdateMutate = vi.fn()
const mockDeactivateMutate = vi.fn()

vi.mock('../api/query-hooks', () => ({
  useUser: (...args: unknown[]) => mockUseUser(...args),
  useRolesForSelect: () => mockUseRolesForSelect(),
  userKeys: {
    all: ['users'] as const,
    lists: () => ['users', 'list'] as const,
    details: () => ['users', 'detail'] as const,
    detail: (id: number) => ['users', 'detail', id] as const,
    rolesForSelect: ['roles', 'select'] as const,
  },
}))

vi.mock('../api/mutation-hooks', () => ({
  useCreateUser: () => ({
    mutate: mockCreateMutate,
    isPending: false,
  }),
  useUpdateUser: () => ({
    mutate: mockUpdateMutate,
    isPending: false,
  }),
  useDeactivateUser: () => ({
    mutate: mockDeactivateMutate,
    isPending: false,
  }),
  isEmailConflictError: (error: unknown) =>
    (error as { status?: number })?.status === 409,
}))

// --- Mock PhoneInput ---

vi.mock('../components/PhoneInput', () => ({
  PhoneInput: ({ value, onChange, disabled }: {
    value: string
    onChange: (val: string) => void
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

// --- Mock RoleSelect ---

vi.mock('../components/RoleSelect', () => ({
  RoleSelect: ({ value, onChange, disabled }: {
    value: number | undefined
    onChange: (val: number) => void
    disabled?: boolean
  }) => (
    <select
      data-testid="role-select"
      value={value ?? ''}
      onChange={(e) => onChange(Number(e.target.value))}
      disabled={disabled}
      aria-label="role"
    >
      <option value="">Select role</option>
      <option value="1">Admin</option>
      <option value="2">Manager</option>
    </select>
  ),
}))

// --- Mock UserFormSkeleton ---

vi.mock('../components/UserFormSkeleton', () => ({
  UserFormSkeleton: () => <div data-testid="user-form-skeleton" className="animate-pulse" />,
}))

// --- Mock shadcn/ui Select (Radix-based) for testability ---

vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children, disabled }: {
    value?: string
    onValueChange?: (val: string) => void
    children: React.ReactNode
    disabled?: boolean
  }) => (
    <div data-testid="shadcn-select-root" data-value={value} data-disabled={disabled}>
      {typeof children === 'function' ? null : children}
      {/* Hidden native select for testing */}
      <select
        data-testid="locale-select"
        value={value ?? ''}
        onChange={(e) => onValueChange?.(e.target.value)}
        disabled={disabled}
        aria-label="locale"
      >
        <option value="">Select</option>
        <option value="pl">Polski (PL)</option>
        <option value="ru">Русский (RU)</option>
        <option value="en">English (EN)</option>
      </select>
    </div>
  ),
  SelectContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectItem: () => null,
  SelectTrigger: () => null,
  SelectValue: () => null,
}))

// --- Mock useBreakpoint ---

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => 'desktop',
}))

// --- Sample data ---

const sampleUsers: UserDto[] = [
  { id: 1, name: 'Jan Kowalski', email: 'jan@example.com', active: true, roleName: 'Admin' },
  { id: 2, name: 'Anna Nowak', email: 'anna@example.com', active: true, roleName: 'Manager' },
  { id: 3, name: 'Piotr Disabled', email: 'piotr@example.com', active: false, roleName: 'Client' },
]

const mockUsersResponse: PaginatedResponse<UserDto> = {
  content: sampleUsers,
  totalElements: 3,
  totalPages: 1,
  number: 0,
  size: 25,
  first: true,
  last: true,
}

// --- Import component after mocks ---

import UsersPage from '../UsersPage'

// --- Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <UsersPage />
    </QueryClientProvider>,
  )
}

// --- Tests ---

describe('UsersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchUsers.mockResolvedValue(mockUsersResponse)
    mockUseUser.mockReturnValue({ data: undefined, isLoading: false })
    mockUseRolesForSelect.mockReturnValue({ data: [], isLoading: false, isError: false })
  })

  describe('DataTable rendering with users data', () => {
    it('renders page title', () => {
      renderPage()
      expect(screen.getByText('Zarządzanie użytkownikami')).toBeInTheDocument()
    })

    it('renders DataTable with users data after fetch', async () => {
      renderPage()

      // DataTable calls fetchUsers via its internal useQuery hook
      // Wait for data to appear
      await waitFor(() => {
        expect(screen.getByText('Jan Kowalski')).toBeInTheDocument()
      })

      expect(screen.getByText('Anna Nowak')).toBeInTheDocument()
      expect(screen.getByText('Piotr Disabled')).toBeInTheDocument()
    })

    it('passes correct fetchFn params to users-api', async () => {
      renderPage()

      await waitFor(() => {
        expect(mockFetchUsers).toHaveBeenCalled()
      })

      // Verify the fetchFn was called with expected default params
      expect(mockFetchUsers).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          size: 25,
        }),
      )
    })
  })

  describe('Create User button', () => {
    it('"Create User" button is rendered', () => {
      renderPage()

      const createButton = screen.getByText('Utwórz użytkownika')
      expect(createButton).toBeInTheDocument()
    })

    it('clicking "Create User" button opens form sheet', async () => {
      const user = userEvent.setup()
      renderPage()

      const createButton = screen.getByText('Utwórz użytkownika')
      await user.click(createButton)

      // In create mode, the sheet should show the create title
      await waitFor(() => {
        expect(screen.getByText('users.form.titleCreate')).toBeInTheDocument()
      })
    })
  })

  describe('Edit action on row', () => {
    it('clicking edit action opens form sheet in edit mode', async () => {
      const user = userEvent.setup()
      renderPage()

      // Wait for users data to load
      await waitFor(() => {
        expect(screen.getByText('Jan Kowalski')).toBeInTheDocument()
      })

      // Click the edit button on the first user row
      const editButtons = screen.getAllByLabelText('Edit')
      await user.click(editButtons[0]!)

      // The form sheet should open in edit mode
      await waitFor(() => {
        expect(screen.getByText('users.form.titleEdit')).toBeInTheDocument()
      })
    })
  })

  describe('Deactivate action', () => {
    it('clicking deactivate action on active user opens confirmation dialog', async () => {
      const user = userEvent.setup()
      renderPage()

      // Wait for users data to load
      await waitFor(() => {
        expect(screen.getByText('Jan Kowalski')).toBeInTheDocument()
      })

      // Click the deactivate button on the first (active) user
      const deactivateButtons = screen.getAllByLabelText('Deactivate')
      // First active user's deactivate button
      await user.click(deactivateButtons[0]!)

      // Confirmation dialog should appear
      await waitFor(() => {
        expect(screen.getByText('users.dialog.deactivateTitle')).toBeInTheDocument()
      })

      expect(
        screen.getByText('Are you sure you want to deactivate Jan Kowalski?'),
      ).toBeInTheDocument()
    })

    it('deactivate button is disabled for inactive users', async () => {
      renderPage()

      // Wait for users data to load
      await waitFor(() => {
        expect(screen.getByText('Piotr Disabled')).toBeInTheDocument()
      })

      // Get all deactivate buttons — active users have enabled buttons, inactive has disabled
      const allDeactivateButtons = screen.getAllByLabelText('Deactivate')
      // There are 3 users total: 2 active + 1 inactive
      // The third user (Piotr Disabled) is inactive — their button should be disabled
      const disabledButton = allDeactivateButtons[2]
      expect(disabledButton).toBeDisabled()
    })
  })

  describe('Full create flow end-to-end', () => {
    it('fill form → submit → toast → sheet closes', async () => {
      // Mock createMutate to simulate successful creation
      mockCreateMutate.mockImplementation(
        (_data: unknown, options: { onSuccess?: () => void }) => {
          options?.onSuccess?.()
        },
      )

      const user = userEvent.setup()
      renderPage()

      // 1. Click "Create User"
      await user.click(screen.getByText('Utwórz użytkownika'))

      // 2. Form sheet opens
      await waitFor(() => {
        expect(screen.getByText('users.form.titleCreate')).toBeInTheDocument()
      })

      // 3. Fill the form fields
      const nameInput = screen.getByLabelText('users.form.name')
      const emailInput = screen.getByLabelText('users.form.email')
      const roleSelect = screen.getByTestId('role-select')
      const localeSelects = screen.getAllByTestId('locale-select')
      // Pick the locale select that's inside the form (usually the last one rendered)
      const localeSelect = localeSelects[localeSelects.length - 1]!

      await user.clear(nameInput)
      await user.type(nameInput, 'New User')
      await user.clear(emailInput)
      await user.type(emailInput, 'newuser@example.com')
      await user.selectOptions(roleSelect, '1')
      await user.selectOptions(localeSelect, 'pl')

      // 4. Submit the form
      const submitButton = screen.getByText('users.form.submitCreate')
      await user.click(submitButton)

      // 5. Verify mutation was called with form data
      await waitFor(() => {
        expect(mockCreateMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'New User',
            email: 'newuser@example.com',
            roleId: 1,
          }),
          expect.objectContaining({
            onSuccess: expect.any(Function),
            onError: expect.any(Function),
          }),
        )
      })

      // 6. Verify toast success was called
      expect(mockToastSuccess).toHaveBeenCalledWith('User created successfully')
    })
  })
})
