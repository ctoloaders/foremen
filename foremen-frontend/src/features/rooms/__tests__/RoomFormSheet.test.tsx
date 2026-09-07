import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { RoomFormSheet } from '../components/RoomFormSheet'
import type { PaginatedResponse, RoomExtendedDto } from '../types'

// --- i18n: return the raw key so assertions can target stable labels. Also
//     export `initReactI18next` because `@/lib/api-client` (mocked with
//     importOriginal) transitively imports `src/lib/i18n.ts`, which calls
//     `i18n.use(initReactI18next)` at module load. ---
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && 'index' in opts ? `${key}:${opts.index}` : key,
    i18n: { changeLanguage: vi.fn() },
  }),
  initReactI18next: { type: '3rdParty', init: vi.fn() },
}))

// --- Room mutations: capture the payload passed on submit ---
const mockCreateMutate = vi.fn()
const mockUpdateMutate = vi.fn()
vi.mock('../api/mutation-hooks', () => ({
  useCreateRoom: () => ({ mutate: mockCreateMutate, isPending: false }),
  useUpdateRoom: () => ({ mutate: mockUpdateMutate, isPending: false }),
}))

// --- Room detail query (edit prefill) — overridable per-test ---
const mockUseRoom = vi.fn()
vi.mock('../api/query-hooks', () => ({
  useRoom: (...args: unknown[]) => mockUseRoom(...args),
}))

// --- apiRequest: feeds the project + room-type reference selectors ---
const mockApiRequest = vi.fn()
vi.mock('@/lib/api-client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api-client')>()
  return {
    ...actual,
    apiRequest: (...args: unknown[]) => mockApiRequest(...args),
  }
})

// --- Fixtures ---

interface ReferenceOption {
  id: number
  name?: string
  code?: string
}

function optionsPage(content: ReferenceOption[]): PaginatedResponse<ReferenceOption> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 200,
    number: 0,
  }
}

const PROJECTS: ReferenceOption[] = [{ id: 1, name: 'Skyline Tower' }]
const ROOM_TYPES: ReferenceOption[] = [{ id: 2, name: 'Living Room' }]

/** Route the mocked `apiRequest` by URL to the right reference list. */
function installApiRequest() {
  mockApiRequest.mockImplementation((url: string) => {
    if (url.startsWith('/api/projects')) return Promise.resolve(optionsPage(PROJECTS))
    if (url.startsWith('/api/room-types')) return Promise.resolve(optionsPage(ROOM_TYPES))
    return Promise.resolve(optionsPage([]))
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

function renderSheet(props: Partial<React.ComponentProps<typeof RoomFormSheet>> = {}) {
  const queryClient = createQueryClient()
  const defaultProps = {
    open: true,
    mode: 'create' as const,
    roomId: null,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    ...props,
  }
  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <RoomFormSheet {...defaultProps} />
      </QueryClientProvider>,
    ),
    props: defaultProps,
  }
}

describe('RoomFormSheet', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    installApiRequest()
    // Default: no room loaded (create mode).
    mockUseRoom.mockReturnValue({ data: undefined, isLoading: false })
  })

  it('adds a per-wall opening and updates form state (opening row appears)', async () => {
    const user = userEvent.setup()
    renderSheet()

    // Start with no walls → the "no walls" hint is shown and there are no wall groups.
    expect(screen.getByText('rooms.form.noWalls')).toBeInTheDocument()
    expect(screen.queryByTestId('room-wall-0')).not.toBeInTheDocument()

    // Add a wall.
    await user.click(screen.getByText('rooms.form.addWall'))
    const wall = await screen.findByTestId('room-wall-0')
    expect(wall).toBeInTheDocument()

    // No opening rows yet on the fresh wall.
    expect(screen.queryByTestId('room-opening-0-0')).not.toBeInTheDocument()

    // Add an opening on the wall → the opening row appears (state updated).
    await user.click(within(wall).getByText('rooms.form.addOpening'))
    const opening = await screen.findByTestId('room-opening-0-0')
    expect(opening).toBeInTheDocument()

    // The opening row exposes the type/count/height/width quick-entry controls.
    expect(within(opening).getByLabelText('rooms.form.openingType')).toBeInTheDocument()
    expect(within(opening).getByLabelText('rooms.form.openingCount')).toBeInTheDocument()
    expect(within(opening).getByLabelText('rooms.form.openingHeight')).toBeInTheDocument()
    expect(within(opening).getByLabelText('rooms.form.openingWidth')).toBeInTheDocument()
  })

  it('manual mode (no walls) accepts direct floorArea/perimeter input', async () => {
    const user = userEvent.setup()
    renderSheet()

    // With no walls the metrics are editable Manual inputs, not read-only.
    expect(screen.queryByTestId('room-floorArea-calculated')).not.toBeInTheDocument()
    expect(screen.queryByTestId('room-perimeter-calculated')).not.toBeInTheDocument()

    const floorArea = screen.getByLabelText('rooms.form.floorArea') as HTMLInputElement
    const perimeter = screen.getByLabelText('rooms.form.perimeter') as HTMLInputElement

    // They are real, editable number inputs.
    expect(floorArea).toBeInstanceOf(HTMLInputElement)
    expect(floorArea).not.toBeDisabled()
    expect(perimeter).not.toBeDisabled()

    await user.type(floorArea, '42.5')
    await user.type(perimeter, '30')

    expect(floorArea.value).toBe('42.5')
    expect(perimeter.value).toBe('30')
  })

  it('geometry mode (wall added) shows the derived metrics read-only', async () => {
    const user = userEvent.setup()
    renderSheet()

    // Manual inputs before geometry exists.
    expect(screen.getByLabelText('rooms.form.floorArea')).toBeInTheDocument()

    // Adding a wall makes geometry present → metrics switch to read-only "Calculated".
    await user.click(screen.getByText('rooms.form.addWall'))
    await screen.findByTestId('room-wall-0')

    // The five derived metrics render as read-only "Calculated" cells...
    for (const key of ['floorArea', 'wallArea', 'perimeter', 'doorArea', 'windowArea']) {
      const cell = await screen.findByTestId(`room-${key}-calculated`)
      expect(cell).toBeInTheDocument()
      expect(within(cell).getByText('rooms.source.calculated')).toBeInTheDocument()
    }

    // ...and the editable manual inputs are gone.
    expect(screen.queryByLabelText('rooms.form.floorArea')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('rooms.form.perimeter')).not.toBeInTheDocument()
  })

  it('edit mode surfaces backend-calculated values in the read-only cells when geometry is present', async () => {
    const room: RoomExtendedDto = {
      id: 5,
      projectId: 1,
      roomTypeId: 2,
      label: 'Suite 1',
      ceilingHeight: 2.7,
      internalCorners: 0,
      doorCount: 1,
      windowCount: 1,
      doorHeight: 2,
      doorWidth: 0.9,
      windowHeight: 1.2,
      windowWidth: 1.5,
      wallGap: 0,
      finishGap: 0,
      geometry: {
        vertices: [
          { x: 0, y: 0 },
          { x: 4, y: 0 },
          { x: 4, y: 3 },
        ],
        walls: [{ wallGap: 0, finishGap: 0, openings: [{ type: 'DOOR', count: 1, height: 2, width: 0.9 }] }],
      },
      floorArea: 12,
      wallArea: 25.5,
      perimeter: 14,
      doorArea: 1.8,
      windowArea: 1.8,
      floorAreaSource: 'CALCULATED',
      wallAreaSource: 'CALCULATED',
      perimeterSource: 'CALCULATED',
      doorAreaSource: 'CALCULATED',
      windowAreaSource: 'CALCULATED',
    }
    mockUseRoom.mockReturnValue({ data: room, isLoading: false })

    renderSheet({ mode: 'edit', roomId: 5 })

    const floorCell = await screen.findByTestId('room-floorArea-calculated')
    expect(within(floorCell).getByText('12')).toBeInTheDocument()
    const perimeterCell = await screen.findByTestId('room-perimeter-calculated')
    expect(within(perimeterCell).getByText('14')).toBeInTheDocument()
  })
})
