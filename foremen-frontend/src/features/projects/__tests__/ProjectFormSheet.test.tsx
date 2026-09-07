import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { ProjectFormSheet } from '../components/ProjectFormSheet'
import type { CreateProjectRequest } from '../types'
import type { PlaceDetailsDto, PlacePredictionDto } from '../types'
import type { PaginatedResponse } from '@/components/data-table'
import type { UserDto, UserExtendedDto } from '@/features/users/types'

// --- i18n: return the raw key so assertions can target stable labels. Also
//     export `initReactI18next` because `@/lib/api-client` (mocked with
//     importOriginal) transitively imports `src/lib/i18n.ts`, which calls
//     `i18n.use(initReactI18next)` at module load. ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { changeLanguage: vi.fn() },
  }),
  initReactI18next: { type: '3rdParty', init: vi.fn() },
}))

// --- Toasts ---
const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('sonner', () => ({
  toast: {
    success: (...args: unknown[]) => mockToastSuccess(...args),
    error: (...args: unknown[]) => mockToastError(...args),
  },
}))

// --- Address proxy API (`../api/address-api`) ---
const mockAutocompleteAddress = vi.fn()
const mockFetchAddressDetails = vi.fn()
vi.mock('../api/address-api', () => ({
  autocompleteAddress: (...args: unknown[]) => mockAutocompleteAddress(...args),
  fetchAddressDetails: (...args: unknown[]) => mockFetchAddressDetails(...args),
}))

// --- Project mutations: capture the create payload on submit ---
const mockCreateMutate = vi.fn()
const mockUpdateMutate = vi.fn()
vi.mock('../api/mutation-hooks', () => ({
  useCreateProject: () => ({ mutate: mockCreateMutate, isPending: false }),
  useUpdateProject: () => ({ mutate: mockUpdateMutate, isPending: false }),
}))

// --- Project detail query (edit prefill; unused in create tests) ---
vi.mock('../api/query-hooks', () => ({
  useProject: () => ({ data: undefined, isLoading: false }),
}))

// --- apiRequest: drives TeamMemberSelect (`/api/users`, `/api/users/{id}`) and
//     ExistingClientSelect (`/api/users?...role.code==CLIENT`). Keep the real
//     ApiError so the form's `instanceof ApiError` branch stays intact. ---
const mockApiRequest = vi.fn()
vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: (...args: unknown[]) => mockApiRequest(...args),
  }
})

// --- Radix Popover: render content unconditionally. The real portal + pointer
//     capture APIs are unreliable in jsdom (same approach as the users specs). ---
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PopoverTrigger: ({ children }: { children: React.ReactNode; asChild?: boolean }) => (
    <div>{children}</div>
  ),
  PopoverContent: ({ children }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="popover-content">{children}</div>
  ),
}))

// --- PhoneInput: simplify to a plain text input for the new-client path ---
vi.mock('@/features/users/components/PhoneInput', () => ({
  PhoneInput: ({
    value,
    onChange,
    disabled,
  }: {
    value: string
    onChange: (val: string | undefined) => void
    disabled?: boolean
  }) => (
    <input
      aria-label="client-phone"
      type="text"
      value={value ?? ''}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}))

// --- Fixtures ---

const PREDICTIONS: PlacePredictionDto[] = [
  { description: '1600 Amphitheatre Pkwy, Mountain View, CA', placeId: 'place-123' },
]

const DETAILS: PlaceDetailsDto = {
  formattedAddress: '1600 Amphitheatre Parkway, Mountain View, CA 94043, USA',
  latitude: 37.4224764,
  longitude: -122.0842499,
  components: [],
}

const TEAM_USER: UserDto = {
  id: 10,
  name: 'Anna Foreman',
  email: 'anna@example.com',
  active: true,
  roleName: 'Foreman',
}

const TEAM_USER_DETAIL: UserExtendedDto = {
  id: 10,
  name: 'Anna Foreman',
  email: 'anna@example.com',
  phone: null,
  roleId: 3,
  roleName: 'Foreman',
  active: true,
  locale: 'pl',
  displayPreferences: null,
}

const CLIENT_USER: UserDto = {
  id: 20,
  name: 'Existing Client',
  email: 'client@example.com',
  active: true,
  roleName: 'Client',
}

function usersPage(content: UserDto[]): PaginatedResponse<UserDto> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    numberOfElements: content.length,
  } as PaginatedResponse<UserDto>
}

/**
 * Route the mocked `apiRequest` by URL:
 *  - `/api/users/{id}`  → the picked team member's detail (carries roleId).
 *  - `/api/users?...role.code==CLIENT` → the CLIENT-only list for ExistingClientSelect.
 *  - `/api/users?...`   → the team-member option list.
 */
function installApiRequest() {
  mockApiRequest.mockImplementation((url: string) => {
    if (/^\/api\/users\/\d+/.test(url)) {
      return Promise.resolve(TEAM_USER_DETAIL)
    }
    // The ExistingClientSelect query encodes the RSQL `role.code==CLIENT` clause
    // (`==` → `%3D%3D`); decode before matching so we don't depend on encoding.
    if (decodeURIComponent(url).includes('role.code==CLIENT')) {
      return Promise.resolve(usersPage([CLIENT_USER]))
    }
    return Promise.resolve(usersPage([TEAM_USER]))
  })
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderSheet(props: Partial<React.ComponentProps<typeof ProjectFormSheet>> = {}) {
  const queryClient = createQueryClient()
  const defaultProps = {
    open: true,
    mode: 'create' as const,
    projectId: null,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    ...props,
  }
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <ProjectFormSheet {...defaultProps} />
      </QueryClientProvider>,
    ),
    props: defaultProps,
  }
}

/** Capture the payload passed to the create mutation on submit (first arg). */
function lastCreatePayload(): CreateProjectRequest {
  const calls = mockCreateMutate.mock.calls
  const call = calls[calls.length - 1]
  return call?.[0] as CreateProjectRequest
}

describe('ProjectFormSheet (create mode)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAutocompleteAddress.mockResolvedValue(PREDICTIONS)
    mockFetchAddressDetails.mockResolvedValue(DETAILS)
    // Fire the mutation's onSuccess so the toast/onSuccess branch executes.
    mockCreateMutate.mockImplementation(
      (_payload: unknown, options: { onSuccess?: () => void }) => options?.onSuccess?.(),
    )
    installApiRequest()
  })

  it('renders the create form with base fields, team select, and client block', async () => {
    renderSheet()

    expect(screen.getByText('projects.form.titleCreate')).toBeInTheDocument()
    expect(screen.getByLabelText('projects.form.name')).toBeInTheDocument()
    // Team select + client block are create-mode only.
    expect(screen.getByTestId('project-team-select')).toBeInTheDocument()
    expect(screen.getByText('projects.form.client.title')).toBeInTheDocument()
    expect(screen.getByText('projects.form.submitCreate')).toBeInTheDocument()
  })

  it('resolves a selected Google prediction and stores place fields in the create payload', async () => {
    const user = userEvent.setup()
    renderSheet()

    // Type into the address autocomplete (>= MIN_QUERY_LENGTH to trigger a search).
    const addressInput = screen.getByLabelText('projects.form.address')
    await user.type(addressInput, '1600 Amphitheatre')

    // Debounced (300ms) autocomplete fires and the prediction appears.
    await waitFor(() => {
      expect(mockAutocompleteAddress).toHaveBeenCalled()
    })
    const prediction = await screen.findByText(PREDICTIONS[0]!.description)

    // Selecting it resolves details via the mocked `/api/addresses/details`.
    await user.click(prediction)
    await waitFor(() => {
      expect(mockFetchAddressDetails).toHaveBeenCalledWith('place-123')
    })

    // Name is required; fill it so the submit passes validation.
    await user.type(screen.getByLabelText('projects.form.name'), 'Skyline Tower')

    await user.click(screen.getByText('projects.form.submitCreate'))

    await waitFor(() => {
      expect(mockCreateMutate).toHaveBeenCalled()
    })

    const payload = lastCreatePayload()
    // The resolved place fields (Req 8.4) are carried on the create payload.
    expect(payload.googlePlaceId).toBe('place-123')
    expect(payload.formattedAddress).toBe(DETAILS.formattedAddress)
    expect(payload.latitude).toBe(DETAILS.latitude)
    expect(payload.longitude).toBe(DETAILS.longitude)
    expect(payload.name).toBe('Skyline Tower')

    // A success toast + onSuccess fire on the mutation success branch.
    expect(mockToastSuccess).toHaveBeenCalledWith('projects.toast.createSuccess')
  })

  it('lets a team member be selected and includes it in the create payload', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.type(screen.getByLabelText('projects.form.name'), 'Team Project')

    // The team option list (mocked `/api/users`) renders inside the (mocked) popover.
    const teamOption = await screen.findByText('Anna Foreman — Foreman')
    await user.click(teamOption)

    // Selecting resolves the user's company role id via `/api/users/{id}` and
    // adds a member chip.
    await waitFor(() => {
      expect(mockApiRequest).toHaveBeenCalledWith('/api/users/10')
    })

    await user.click(screen.getByText('projects.form.submitCreate'))

    await waitFor(() => {
      expect(mockCreateMutate).toHaveBeenCalled()
    })

    const payload = lastCreatePayload()
    expect(payload.members).toEqual([{ userId: 10, projectRoleId: 3 }])
  })

  it('supports the existing-client case and sends existingClientUserId', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.type(screen.getByLabelText('projects.form.name'), 'Existing Client Project')

    // Default client mode is "existing"; the CLIENT-only list renders in the popover.
    const clientOption = await screen.findByText('Existing Client')
    await user.click(clientOption)

    await user.click(screen.getByText('projects.form.submitCreate'))

    await waitFor(() => {
      expect(mockCreateMutate).toHaveBeenCalled()
    })

    const payload = lastCreatePayload()
    expect(payload.client).toEqual({ existingClientUserId: 20 })
  })

  it('supports the new-client case (name/email) and sends the newClient block', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.type(screen.getByLabelText('projects.form.name'), 'New Client Project')

    // Switch the client block to the "new" tab.
    await user.click(screen.getByText('projects.form.client.newTab'))

    // Fill the reused invite fields (name + email; no role selector).
    await user.type(screen.getByLabelText('projects.form.client.name'), 'Brand New Client')
    await user.type(
      screen.getByLabelText('projects.form.client.email'),
      'newclient@example.com',
    )

    await user.click(screen.getByText('projects.form.submitCreate'))

    await waitFor(() => {
      expect(mockCreateMutate).toHaveBeenCalled()
    })

    const payload = lastCreatePayload()
    expect(payload.client).toEqual({
      newClient: {
        name: 'Brand New Client',
        email: 'newclient@example.com',
        phone: null,
        locale: null,
      },
    })
  })
})
