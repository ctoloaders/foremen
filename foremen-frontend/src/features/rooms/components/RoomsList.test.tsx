import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { RoomDto, PaginatedResponse } from '../types'

// --- Mocks ---

// Keep the real react-i18next module (i18n.ts wires initReactI18next, pulled in
// transitively via usePermission → auth-store → api-client → i18n) and only
// override useTranslation. The map mirrors the ru.json `rooms.*` block plus the
// DataTable empty-state key so the table renders real localized labels.
const translations: Record<string, string> = {
  'rooms.actions.create': 'Новое помещение',
  'rooms.table.project': 'Проект',
  'rooms.table.roomType': 'Тип помещения',
  'rooms.table.label': 'Метка',
  'rooms.table.floorArea': 'Площадь пола (м²)',
  'rooms.table.wallArea': 'Площадь стен (м²)',
  'rooms.table.perimeter': 'Периметр (мб)',
  'rooms.table.ceilingHeight': 'Высота (мб)',
  'rooms.source.calculated': 'Рассчитано',
  'rooms.source.manual': 'Вручную',
  'common.edit': 'Редактировать',
  'common.delete': 'Удалить',
  'dataTable.search': 'Поиск...',
  'dataTable.empty.filtered': 'Помещения не найдены',
  'audit.button.viewAudit': 'История изменений',
}

vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => translations[key] ?? key,
      i18n: { language: 'ru', changeLanguage: vi.fn() },
    }),
  }
})

const mockUseBreakpoint = vi.fn<() => 'desktop' | 'tablet' | 'mobile'>(() => 'desktop')

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockUseBreakpoint(),
}))

// Mock the rooms list fetch so the DataTable receives a controlled page. The
// component under test wires `fetchRooms` behind its own `fetchFn` adapter.
const mockFetchRooms = vi.fn<
  (params: {
    page?: number
    size?: number
    query?: string
    sort?: string[]
  }) => Promise<PaginatedResponse<RoomDto>>
>()

vi.mock('../api/rooms-api', () => ({
  fetchRooms: (params: {
    page?: number
    size?: number
    query?: string
    sort?: string[]
  }) => mockFetchRooms(params),
}))

// Import the component AFTER mocks are set up
import { RoomsList } from './RoomsList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateRoom = vi.fn()
const mockOnEditRoom = vi.fn()
const mockOnDeleteRoom = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <RoomsList
        onCreateRoom={mockOnCreateRoom}
        onEditRoom={mockOnEditRoom}
        onDeleteRoom={mockOnDeleteRoom}
      />
    </QueryClientProvider>,
  )
}

/**
 * Build a room list DTO with sensible defaults; override per test. Uses the
 * backend list DTO shape: resolved reference display values (`projectName`,
 * `roomTypeName`) + the five `{ value, source }` MeasureValues.
 */
function makeRoom(overrides: Partial<RoomDto> = {}): RoomDto {
  return {
    id: 1,
    projectId: 10,
    projectName: 'Проект А',
    roomTypeId: 20,
    roomTypeName: 'Ванная',
    label: 'Ванная 1',
    ceilingHeight: 2.7,
    internalCorners: null,
    doorCount: null,
    windowCount: null,
    doorHeight: null,
    doorWidth: null,
    windowHeight: null,
    windowWidth: null,
    wallGap: null,
    finishGap: null,
    geometry: null,
    floorArea: { value: 12.5, source: 'CALCULATED' },
    wallArea: { value: 30.0, source: 'CALCULATED' },
    perimeter: { value: 14.0, source: 'CALCULATED' },
    doorArea: { value: null, source: null },
    windowArea: { value: null, source: null },
    ...overrides,
  }
}

// Two rooms: one fully CALCULATED (geometry-derived), one fully MANUAL
// (typed). This lets the source-indicator assertions cover both badge labels
// and the non-empty render assertions cover row count == record count.
const sampleRooms: RoomDto[] = [
  makeRoom({
    id: 1,
    projectName: 'Проект А',
    roomTypeName: 'Ванная',
    label: 'Ванная 1',
    ceilingHeight: 2.7,
    floorArea: { value: 12.5, source: 'CALCULATED' },
    wallArea: { value: 30, source: 'CALCULATED' },
    perimeter: { value: 14, source: 'CALCULATED' },
  }),
  makeRoom({
    id: 2,
    projectName: 'Проект Б',
    roomTypeName: 'Кухня',
    label: 'Кухня 1',
    ceilingHeight: 3,
    floorArea: { value: 20, source: 'MANUAL' },
    wallArea: { value: 45, source: 'MANUAL' },
    perimeter: { value: 18, source: 'MANUAL' },
  }),
]

function mockFetchSuccess(rooms: RoomDto[] = sampleRooms) {
  mockFetchRooms.mockResolvedValue({
    content: rooms,
    totalPages: 1,
    totalElements: rooms.length,
    number: 0,
    size: 10,
  } as PaginatedResponse<RoomDto>)
}

/** Count only the data rows (rows inside <tbody>), excluding the header row. */
function dataRowCount(): number {
  return document.querySelectorAll('tbody tr').length
}

// --- Tests ---

describe('RoomsList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    // FOR-04-14 Req 8.9 gates Create/Edit/Delete behind ROOMS permissions.
    // Default to an ADMIN user (matrix bypass) so the full action set renders;
    // permission-gating tests override this seed.
    useAuthStore.setState({
      user: {
        id: 1,
        name: 'Admin',
        email: 'admin@example.com',
        roleCode: 'ADMIN',
        permissions: [],
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  describe('Non-empty rendering (Req 9.1)', () => {
    it('renders project/roomType/label/floorArea/wallArea/perimeter/ceilingHeight with row count == record count', async () => {
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load.
      await waitFor(() => {
        expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      })

      // project + roomType reference display values render for each row.
      expect(screen.getByText('Проект А')).toBeInTheDocument()
      expect(screen.getByText('Проект Б')).toBeInTheDocument()
      expect(screen.getByText('Ванная')).toBeInTheDocument()
      expect(screen.getByText('Кухня')).toBeInTheDocument()

      // label renders for each row.
      expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      expect(screen.getByText('Кухня 1')).toBeInTheDocument()

      // floorArea / wallArea / perimeter numeric values render (via MeasureValueCell).
      expect(screen.getByText('12.5')).toBeInTheDocument() // room 1 floorArea
      expect(screen.getByText('30')).toBeInTheDocument() // room 1 wallArea
      expect(screen.getByText('14')).toBeInTheDocument() // room 1 perimeter
      expect(screen.getByText('20')).toBeInTheDocument() // room 2 floorArea
      expect(screen.getByText('45')).toBeInTheDocument() // room 2 wallArea
      expect(screen.getByText('18')).toBeInTheDocument() // room 2 perimeter

      // ceilingHeight renders for each row.
      expect(screen.getByText('2.7')).toBeInTheDocument()
      expect(screen.getByText('3')).toBeInTheDocument()

      // Row count equals the number of supplied records.
      expect(dataRowCount()).toBe(sampleRooms.length)
    })

    it('shows a source indicator badge on each area/perimeter cell', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      })

      // Room 1: floorArea/wallArea/perimeter are CALCULATED → three "Рассчитано" badges.
      // Room 2: same three metrics are MANUAL → three "Вручную" badges.
      expect(screen.getAllByText('Рассчитано')).toHaveLength(3)
      expect(screen.getAllByText('Вручную')).toHaveLength(3)
    })
  })

  describe('Permission-gated controls (Req 9.4)', () => {
    it('ADMIN sees the create button and per-row edit/delete actions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      })

      // Create button present and wired.
      const createButton = screen.getByText('Новое помещение')
      expect(createButton).toBeInTheDocument()
      fireEvent.click(createButton)
      expect(mockOnCreateRoom).toHaveBeenCalledTimes(1)

      // Every row gets edit + delete actions.
      expect(screen.getAllByLabelText('Редактировать')).toHaveLength(
        sampleRooms.length,
      )
      expect(screen.getAllByLabelText('Удалить')).toHaveLength(
        sampleRooms.length,
      )
    })

    it('FOREMAN (READ/UPDATE, no CREATE/DELETE) shows edit but hides create and delete', async () => {
      useAuthStore.setState({
        user: {
          id: 2,
          name: 'Foreman',
          email: 'foreman@example.com',
          roleCode: 'FOREMAN',
          permissions: [{ resource: 'ROOMS', operations: ['READ', 'UPDATE'] }],
        },
      })
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      })

      // Create + delete hidden; edit present.
      expect(screen.queryByText('Новое помещение')).not.toBeInTheDocument()
      expect(screen.getAllByLabelText('Редактировать')).toHaveLength(
        sampleRooms.length,
      )
      expect(screen.queryByLabelText('Удалить')).not.toBeInTheDocument()
    })

    it('READ-only role hides the create button and all row edit/delete actions', async () => {
      useAuthStore.setState({
        user: {
          id: 3,
          name: 'Client',
          email: 'client@example.com',
          roleCode: 'CLIENT',
          permissions: [{ resource: 'ROOMS', operations: ['READ'] }],
        },
      })
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Ванная 1')).toBeInTheDocument()
      })

      expect(screen.queryByText('Новое помещение')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Редактировать')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Удалить')).not.toBeInTheDocument()
    })
  })

  describe('Empty state (Req 9.1)', () => {
    it('shows the empty state with zero data rows when the result is empty', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Помещения не найдены')).toBeInTheDocument()
      })

      // No data rows are rendered.
      expect(dataRowCount()).toBe(0)
    })
  })
})
