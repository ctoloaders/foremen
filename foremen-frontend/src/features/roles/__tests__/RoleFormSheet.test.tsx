import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RoleFormSheet } from '../components/RoleFormSheet'
import type { RoleExtendedDto } from '../types'

// --- Mocks ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { changeLanguage: vi.fn() },
  }),
}))

const mockRoleData: RoleExtendedDto = {
  id: 1,
  code: 'ADMIN',
  nameRU: 'Администратор',
  namePL: 'Administrator',
  descriptionRU: 'Полный доступ',
  descriptionPL: 'Pełny dostęp',
  system: true,
}

const mockUseRole = vi.fn()
const mockCreateMutate = vi.fn()
const mockUpdateMutate = vi.fn()

vi.mock('../api/query-hooks', () => ({
  useRole: (...args: unknown[]) => mockUseRole(...args),
}))

vi.mock('../api/mutation-hooks', () => ({
  useCreateRole: () => ({
    mutate: mockCreateMutate,
    isPending: false,
  }),
  useUpdateRole: () => ({
    mutate: mockUpdateMutate,
    isPending: false,
  }),
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

function renderSheet(props: Partial<React.ComponentProps<typeof RoleFormSheet>> = {}) {
  const queryClient = createQueryClient()
  const defaultProps = {
    open: true,
    mode: 'create' as const,
    roleId: null,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    ...props,
  }

  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <RoleFormSheet {...defaultProps} />
      </QueryClientProvider>,
    ),
    props: defaultProps,
  }
}

// --- Tests ---

describe('RoleFormSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseRole.mockReturnValue({ data: undefined, isLoading: false })
  })

  describe('Create mode', () => {
    it('renders all form fields empty with code field editable', () => {
      renderSheet({ mode: 'create', roleId: null })

      // Code field is present and editable
      const codeInput = screen.getByPlaceholderText('ROLE_CODE')
      expect(codeInput).toBeInTheDocument()
      expect(codeInput).not.toBeDisabled()
      expect(codeInput).toHaveValue('')

      // Name fields are empty
      const nameRU = screen.getByLabelText('roles.form.nameRU')
      const namePL = screen.getByLabelText('roles.form.namePL')
      expect(nameRU).toHaveValue('')
      expect(namePL).toHaveValue('')

      // Description fields are empty
      const descRU = screen.getByLabelText('roles.form.descriptionRU')
      const descPL = screen.getByLabelText('roles.form.descriptionPL')
      expect(descRU).toHaveValue('')
      expect(descPL).toHaveValue('')
    })

    it('displays create mode title and submit button label', () => {
      renderSheet({ mode: 'create' })

      expect(screen.getByText('roles.form.titleCreate')).toBeInTheDocument()
      expect(screen.getByText('roles.form.submitCreate')).toBeInTheDocument()
    })
  })

  describe('Edit mode', () => {
    it('pre-populates fields with role data and shows code as disabled', async () => {
      mockUseRole.mockReturnValue({ data: mockRoleData, isLoading: false })

      renderSheet({ mode: 'edit', roleId: 1 })

      // Code field should be disabled in edit mode (shown as read-only info)
      await waitFor(() => {
        const codeInput = screen.getByDisplayValue('ADMIN')
        expect(codeInput).toBeDisabled()
      })

      // Name fields pre-populated (disabled for system roles)
      await waitFor(() => {
        expect(screen.getByLabelText('roles.form.nameRU')).toHaveValue('Администратор')
        expect(screen.getByLabelText('roles.form.namePL')).toHaveValue('Administrator')
      })

      // Description fields pre-populated
      expect(screen.getByLabelText('roles.form.descriptionRU')).toHaveValue('Полный доступ')
      expect(screen.getByLabelText('roles.form.descriptionPL')).toHaveValue('Pełny dostęp')

      // System roles have name fields disabled
      expect(screen.getByLabelText('roles.form.nameRU')).toBeDisabled()
      expect(screen.getByLabelText('roles.form.namePL')).toBeDisabled()
    })

    it('displays edit mode title and submit button label', () => {
      mockUseRole.mockReturnValue({ data: mockRoleData, isLoading: false })

      renderSheet({ mode: 'edit', roleId: 1 })

      expect(screen.getByText('roles.form.titleEdit')).toBeInTheDocument()
      expect(screen.getByText('roles.form.submitEdit')).toBeInTheDocument()
    })

    it('shows skeleton while loading role data', () => {
      mockUseRole.mockReturnValue({ data: undefined, isLoading: true })

      renderSheet({ mode: 'edit', roleId: 1 })

      // Skeleton should render (animated pulse elements) — content is portaled to document.body
      const pulseElements = document.querySelectorAll('.animate-pulse')
      expect(pulseElements.length).toBeGreaterThan(0)
    })
  })

  describe('Validation', () => {
    it('shows inline errors when submitting with invalid data', async () => {
      renderSheet({ mode: 'create', roleId: null })

      // Submit the form without filling required fields
      const submitButton = screen.getByText('roles.form.submitCreate')
      fireEvent.click(submitButton)

      // Wait for validation error messages to appear
      await waitFor(() => {
        // At minimum, nameRU and namePL are required (min 2 chars)
        // code is required
        const errorMessages = screen.getAllByText(/roles\.validation\./)
        expect(errorMessages.length).toBeGreaterThan(0)
      })
    })

    it('shows code pattern error for invalid code format', async () => {
      renderSheet({ mode: 'create', roleId: null })

      // Fill code with invalid value (lowercase)
      const codeInput = screen.getByPlaceholderText('ROLE_CODE')
      fireEvent.change(codeInput, { target: { value: 'invalid' } })
      fireEvent.blur(codeInput)

      // Fill required name fields to avoid their validation errors confusing the assertion
      fireEvent.change(screen.getByLabelText('roles.form.nameRU'), { target: { value: 'Тест' } })
      fireEvent.change(screen.getByLabelText('roles.form.namePL'), { target: { value: 'Test' } })

      // Submit to trigger full validation
      fireEvent.click(screen.getByText('roles.form.submitCreate'))

      await waitFor(() => {
        expect(screen.getByText('roles.validation.codePattern')).toBeInTheDocument()
      })
    })
  })

  describe('Submit flows', () => {
    it('calls createRole mutation with form data on successful create', async () => {
      mockCreateMutate.mockImplementation((_data: unknown, options: { onSuccess?: () => void }) => {
        options?.onSuccess?.()
      })

      const { props } = renderSheet({ mode: 'create', roleId: null })

      // Fill all required fields with valid values
      fireEvent.change(screen.getByPlaceholderText('ROLE_CODE'), { target: { value: 'TEST_ROLE' } })
      fireEvent.change(screen.getByLabelText('roles.form.nameRU'), { target: { value: 'Тестовая роль' } })
      fireEvent.change(screen.getByLabelText('roles.form.namePL'), { target: { value: 'Rola testowa' } })

      // Submit
      fireEvent.click(screen.getByText('roles.form.submitCreate'))

      await waitFor(() => {
        expect(mockCreateMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            code: 'TEST_ROLE',
            nameRU: 'Тестовая роль',
            namePL: 'Rola testowa',
          }),
          expect.objectContaining({ onSuccess: expect.any(Function) }),
        )
      })

      // onSuccess callback should be called
      expect(props.onSuccess).toHaveBeenCalled()
    })

    it('calls updateRole mutation with form data on successful edit', async () => {
      const nonSystemRole: RoleExtendedDto = {
        ...mockRoleData,
        system: false,
      }
      mockUseRole.mockReturnValue({ data: nonSystemRole, isLoading: false })
      mockUpdateMutate.mockImplementation((_data: unknown, options: { onSuccess?: () => void }) => {
        options?.onSuccess?.()
      })

      const { props } = renderSheet({ mode: 'edit', roleId: 1 })

      // Wait for form to be pre-populated
      await waitFor(() => {
        expect(screen.getByLabelText('roles.form.nameRU')).toHaveValue('Администратор')
      })

      // Update a field
      fireEvent.change(screen.getByLabelText('roles.form.nameRU'), { target: { value: 'Новое имя' } })

      // Submit
      fireEvent.click(screen.getByText('roles.form.submitEdit'))

      await waitFor(() => {
        expect(mockUpdateMutate).toHaveBeenCalledWith(
          expect.objectContaining({
            id: 1,
            data: expect.objectContaining({
              nameRU: 'Новое имя',
              namePL: 'Administrator',
            }),
          }),
          expect.objectContaining({ onSuccess: expect.any(Function) }),
        )
      })

      expect(props.onSuccess).toHaveBeenCalled()
    })
  })

  describe('Cancel', () => {
    it('calls onClose when cancel button is clicked', () => {
      const { props } = renderSheet({ mode: 'create', roleId: null })

      const cancelButton = screen.getByText('common.cancel')
      fireEvent.click(cancelButton)

      expect(props.onClose).toHaveBeenCalled()
    })
  })
})
