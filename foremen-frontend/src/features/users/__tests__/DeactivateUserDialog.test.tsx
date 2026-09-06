import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { UserDto } from '../types'

// --- Mocks ---

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, string>) => {
      const translations: Record<string, string> = {
        'users.dialog.deactivateTitle': 'Deactivate User',
        'users.dialog.deactivateDescription': `Are you sure you want to deactivate ${params?.name ?? ''}?`,
        'users.dialog.deactivateConfirm': 'Deactivate',
        'users.errors.network': 'Network error',
        'common.cancel': 'Cancel',
        'common.loading': 'Loading...',
      }
      return translations[key] ?? key
    },
  }),
  // `users-api` now transitively imports `@/lib/i18n` (via the shared Api_Client),
  // which calls `i18n.use(initReactI18next)`. Provide a passthrough plugin so the
  // i18n module initializes under this partial mock.
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

const mockMutate = vi.fn()
vi.mock('../api/mutation-hooks', () => ({
  useDeactivateUser: () => ({
    mutate: mockMutate,
    isPending: false,
  }),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

import { toast } from 'sonner'
import { DeactivateUserDialog } from '../components/DeactivateUserDialog'
import { ApiError } from '../api/users-api'

// --- Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderDialog(props: Partial<Parameters<typeof DeactivateUserDialog>[0]> = {}) {
  const queryClient = createQueryClient()
  const defaultProps = {
    open: true,
    user: { id: 1, name: 'John Doe', email: 'john@example.com', active: true, roleName: 'Admin' } as UserDto,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
  }
  const merged = { ...defaultProps, ...props }
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <DeactivateUserDialog {...merged} />
      </QueryClientProvider>,
    ),
    props: merged,
  }
}

// --- Tests ---

describe('DeactivateUserDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders user name in confirmation message', () => {
    renderDialog()

    expect(screen.getByText('Deactivate User')).toBeInTheDocument()
    expect(screen.getByText('Are you sure you want to deactivate John Doe?')).toBeInTheDocument()
  })

  it('confirm calls DELETE mutation', async () => {
    const user = userEvent.setup()
    renderDialog()

    const confirmBtn = screen.getByRole('button', { name: 'Deactivate' })
    await user.click(confirmBtn)

    expect(mockMutate).toHaveBeenCalledWith(1, expect.objectContaining({
      onSuccess: expect.any(Function),
      onError: expect.any(Function),
    }))
  })

  it('success triggers onSuccess callback', async () => {
    mockMutate.mockImplementation((_id: number, options: { onSuccess: () => void }) => {
      options.onSuccess()
    })
    const user = userEvent.setup()
    const { props } = renderDialog()

    const confirmBtn = screen.getByRole('button', { name: 'Deactivate' })
    await user.click(confirmBtn)

    expect(props.onSuccess).toHaveBeenCalled()
  })

  it('error shows error toast', async () => {
    const apiError = new ApiError(500, 'Server failed')
    mockMutate.mockImplementation((_id: number, options: { onError: (err: Error) => void }) => {
      options.onError(apiError)
    })
    const user = userEvent.setup()
    renderDialog()

    const confirmBtn = screen.getByRole('button', { name: 'Deactivate' })
    await user.click(confirmBtn)

    expect(toast.error).toHaveBeenCalledWith('Server failed')
  })
})
