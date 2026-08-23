import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { UserFormSheet } from '../components/UserFormSheet'
import type { UserExtendedDto } from '../types'

// --- Mocks ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { changeLanguage: vi.fn() },
  }),
}))

const mockToastError = vi.fn()
vi.mock('sonner', () => ({
  toast: {
    error: (...args: unknown[]) => mockToastError(...args),
    success: vi.fn(),
  },
}))

// Mock shadcn Select components to render as native HTML select for testability
vi.mock('@/components/ui/select', () => ({
  Select: ({ children, value, onValueChange, disabled }: {
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

const mockUserData: UserExtendedDto = {
  id: 1,
  name: 'Jan Kowalski',
  email: 'jan@example.com',
  phone: '+48789736625',
  roleId: 2,
  roleName: 'Manager',
  active: true,
  locale: 'pl',
  displayPreferences: null,
}

const mockUseUser = vi.fn()
const mockUseRolesForSelect = vi.fn()
const mockCreateMutate = vi.fn()
const mockUpdateMutate = vi.fn()
let mockCreateIsPending = false
let mockUpdateIsPending = false

vi.mock('../api/query-hooks', () => ({
  useUser: (...args: unknown[]) => mockUseUser(...args),
  useRolesForSelect: () => mockUseRolesForSelect(),
}))

vi.mock('../api/mutation-hooks', () => ({
  useCreateUser: () => ({
    mutate: mockCreateMutate,
    isPending: mockCreateIsPending,
  }),
  useUpdateUser: () => ({
    mutate: mockUpdateMutate,
    isPending: mockUpdateIsPending,
  }),
  isEmailConflictError: (error: unknown) =>
    (error as { status?: number })?.status === 409,
}))

// Mock PhoneInput to simplify form interaction
vi.mock('../components/PhoneInput', () => ({
  PhoneInput: ({ value, onChange, error, disabled }: {
    value: string
    onChange: (val: string) => void
    error?: string
    disabled?: boolean
  }) => (
    <div data-testid="phone-input">
      <input
        type="text"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        aria-label="phone"
      />
      {error && <span>{error}</span>}
    </div>
  ),
}))

// Mock RoleSelect to simplify role assignment in tests
vi.mock('../components/RoleSelect', () => ({
  RoleSelect: ({ value, onChange, error, disabled }: {
    value: number | undefined
    onChange: (val: number) => void
    error?: string
    disabled?: boolean
  }) => (
    <div data-testid="role-select">
      <select
        value={value ?? ''}
        onChange={(e) => onChange(Number(e.target.value))}
        disabled={disabled}
        aria-label="role"
      >
        <option value="">Select role</option>
        <option value="1">Admin</option>
        <option value="2">Manager</option>
      </select>
      {error && <span>{error}</span>}
    </div>
  ),
}))

// Mock UserFormSkeleton
vi.mock('../components/UserFormSkeleton', () => ({
  UserFormSkeleton: () => <div data-testid="user-form-skeleton" className="animate-pulse" />,
}))

// --- Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderSheet(props: Partial<React.ComponentProps<typeof UserFormSheet>> = {}) {
  const queryClient = createQueryClient()
  const defaultProps = {
    open: true,
    mode: 'create' as const,
    userId: null,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    ...props,
  }

  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <UserFormSheet {...defaultProps} />
      </QueryClientProvider>,
    ),
    props: defaultProps,
  }
}

// --- Tests ---

describe('UserFormSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseUser.mockReturnValue({ data: undefined, isLoading: false })
    mockUseRolesForSelect.mockReturnValue({ data: [], isLoading: false, isError: false })
    mockCreateIsPending = false
    mockUpdateIsPending = false
  })

  describe('Create mode', () => {
    it('renders empty fields in create mode', () => {
      renderSheet({ mode: 'create', userId: null })

      // Name field should be empty
      const nameInput = screen.getByLabelText('users.form.name')
      expect(nameInput).toHaveValue('')

      // Email field should be empty
      const emailInput = screen.getByLabelText('users.form.email')
      expect(emailInput).toHaveValue('')

      // Active checkbox should be checked by default
      const activeCheckbox = screen.getByLabelText('users.form.active')
      expect(activeCheckbox).toBeChecked()

      // Submit button should show create label
      expect(screen.getByText('users.form.submitCreate')).toBeInTheDocument()
    })
  })

  describe('Edit mode', () => {
    it('fetches user and pre-populates form in edit mode', async () => {
      mockUseUser.mockReturnValue({ data: mockUserData, isLoading: false })

      renderSheet({ mode: 'edit', userId: 1 })

      // Wait for form to be pre-populated via useEffect + reset
      await waitFor(() => {
        expect(screen.getByLabelText('users.form.name')).toHaveValue('Jan Kowalski')
      })

      expect(screen.getByLabelText('users.form.email')).toHaveValue('jan@example.com')

      // Submit button should show edit label
      expect(screen.getByText('users.form.submitEdit')).toBeInTheDocument()
    })

    it('shows skeleton while loading user data', () => {
      mockUseUser.mockReturnValue({ data: undefined, isLoading: true })

      renderSheet({ mode: 'edit', userId: 1 })

      expect(screen.getByTestId('user-form-skeleton')).toBeInTheDocument()
    })
  })

  describe('Validation', () => {
    it('displays validation errors inline on invalid submit', async () => {
      renderSheet({ mode: 'create', userId: null })

      // Submit without filling required fields
      const submitButton = screen.getByText('users.form.submitCreate')
      fireEvent.click(submitButton)

      // Wait for validation error messages to appear (localized keys)
      await waitFor(() => {
        const errorMessages = screen.getAllByText(/users\.validation\./)
        expect(errorMessages.length).toBeGreaterThan(0)
      })
    })
  })

  describe('Submit button pending state', () => {
    it('disables submit button while mutation is pending', () => {
      mockCreateIsPending = true

      renderSheet({ mode: 'create', userId: null })

      // The button text should indicate loading state
      const loadingButton = screen.getByText('common.loading')
      expect(loadingButton.closest('button')).toBeDisabled()
    })
  })

  describe('Successful create', () => {
    it('calls POST and triggers onSuccess on successful create', async () => {
      mockCreateMutate.mockImplementation(
        (_data: unknown, options: { onSuccess?: () => void }) => {
          options?.onSuccess?.()
        },
      )

      const { props } = renderSheet({ mode: 'create', userId: null })

      // Fill required fields
      fireEvent.change(screen.getByLabelText('users.form.name'), {
        target: { value: 'New User' },
      })
      fireEvent.change(screen.getByLabelText('users.form.email'), {
        target: { value: 'new@example.com' },
      })

      // Select role (mocked as native select)
      const roleSelect = screen.getByLabelText('role')
      fireEvent.change(roleSelect, { target: { value: '1' } })

      // Select locale (mocked shadcn Select renders as native select)
      const localeSelect = screen.getByTestId('locale-select')
      fireEvent.change(localeSelect, { target: { value: 'pl' } })

      // Submit
      fireEvent.click(screen.getByText('users.form.submitCreate'))

      await waitFor(() => {
        expect(mockCreateMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            name: 'New User',
            email: 'new@example.com',
            roleId: 1,
            locale: 'pl',
          }),
          expect.objectContaining({ onSuccess: expect.any(Function) }),
        )
      })

      // onSuccess callback should be called
      expect(props.onSuccess).toHaveBeenCalled()
    })
  })

  describe('Error handling', () => {
    it('shows email exists toast on 409 error', async () => {
      mockCreateMutate.mockImplementation(
        (_data: unknown, options: { onError?: (error: unknown) => void }) => {
          options?.onError?.({ status: 409, message: 'Email already exists' })
        },
      )

      renderSheet({ mode: 'create', userId: null })

      // Fill required fields
      fireEvent.change(screen.getByLabelText('users.form.name'), {
        target: { value: 'New User' },
      })
      fireEvent.change(screen.getByLabelText('users.form.email'), {
        target: { value: 'existing@example.com' },
      })

      // Select role
      const roleSelect = screen.getByLabelText('role')
      fireEvent.change(roleSelect, { target: { value: '1' } })

      // Select locale
      const localeSelect = screen.getByTestId('locale-select')
      fireEvent.change(localeSelect, { target: { value: 'ru' } })

      // Submit
      fireEvent.click(screen.getByText('users.form.submitCreate'))

      await waitFor(() => {
        expect(mockCreateMutate).toHaveBeenCalled()
      })

      // Verify toast.error was called with email exists message
      expect(mockToastError).toHaveBeenCalledWith('users.toast.emailExists')
    })
  })
})
