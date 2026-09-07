import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type {
  ProjectDto,
  ProjectStatus,
  PaginatedResponse,
} from '../types'

// --- Mocks ---

// Stable localized labels for assertions. Keep the real react-i18next module
// (i18n.ts wires initReactI18next, pulled in transitively via usePermission →
// Auth_Store → api-client → i18n) and only override useTranslation. The map
// mirrors the ru.json `projects.*` block so the five status badges render their
// real localized labels.
const translations: Record<string, string> = {
  'projects.actions.create': 'Новый проект',
  'projects.columns.name': 'Название',
  'projects.columns.address': 'Адрес',
  'projects.columns.area': 'Площадь',
  'projects.columns.startDate': 'Дата начала',
  'projects.columns.endDate': 'Дата окончания',
  'projects.columns.status': 'Статус',
  'projects.columns.members': 'Команда',
  'projects.columns.client': 'Клиент',
  'projects.status.DRAFT': 'Черновик',
  'projects.status.ACTIVE': 'Активный',
  'projects.status.ON_HOLD': 'Приостановлен',
  'projects.status.COMPLETED': 'Завершён',
  'projects.status.CANCELLED': 'Отменён',
  'projects.members.empty': 'Нет участников команды',
  'projects.client.empty': 'Клиент не указан',
  'common.edit': 'Редактировать',
  'common.delete': 'Удалить',
  'dataTable.search': 'Поиск...',
  'dataTable.empty.filtered': 'Проекты не найдены',
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

// Mock the projects list fetch so the DataTable receives a controlled page. The
// component under test wires `fetchProjects` behind its own `fetchFn` adapter.
const mockFetchProjects = vi.fn<
  (params: {
    page?: number
    size?: number
    query?: string
    sort?: string[]
  }) => Promise<PaginatedResponse<ProjectDto>>
>()

vi.mock('../api/projects-api', () => ({
  fetchProjects: (params: {
    page?: number
    size?: number
    query?: string
    sort?: string[]
  }) => mockFetchProjects(params),
}))

// The team members and client filters are now nested-entity column filters
// (FOR-04-bugs Bug 11): they live on the members/client columns as
// ColumnConfig.reference descriptors and only fetch `/api/users` when their
// column filter popover is opened. These list tests never open those popovers,
// so no stubbing of the users options endpoint is needed. ProjectsList imports
// only the pure `MEMBERS_USER_ID_PATH` / `CLIENT_ROLE_PREDICATE` constants from
// those modules, so they are left un-mocked. (The filter components' own
// behavior is covered by ProjectMembersFilter/ProjectClientFilter tests.)

// Import the component AFTER mocks are set up
import { ProjectsList } from '../components/ProjectsList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateProject = vi.fn()
const mockOnEditProject = vi.fn()
const mockOnDeleteProject = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectsList
        onCreateProject={mockOnCreateProject}
        onEditProject={mockOnEditProject}
        onDeleteProject={mockOnDeleteProject}
      />
    </QueryClientProvider>,
  )
}

/**
 * Build a project row with sensible defaults; override per test. Uses the
 * backend list DTO shape (base fields + `members` + derived `client`).
 */
function makeProject(overrides: Partial<ProjectDto> = {}): ProjectDto {
  return {
    id: 1,
    name: 'Проект А',
    address: 'ул. Ленина, 1',
    googlePlaceId: null,
    formattedAddress: null,
    latitude: null,
    longitude: null,
    area: 120.5,
    startDate: '2024-01-01',
    endDate: '2024-12-31',
    status: 'DRAFT',
    members: [],
    client: null,
    ...overrides,
  }
}

/**
 * One project per each of the five statuses, so the non-empty render assertions
 * (row count == record count) and the per-status badge assertions can both use
 * the same page.
 */
const allStatuses: ProjectStatus[] = [
  'DRAFT',
  'ACTIVE',
  'ON_HOLD',
  'COMPLETED',
  'CANCELLED',
]

const sampleProjects: ProjectDto[] = allStatuses.map((status, i) =>
  makeProject({
    id: i + 1,
    name: `Проект ${status}`,
    address: `Адрес ${i + 1}`,
    area: 100 + i,
    startDate: `2024-0${i + 1}-01`,
    endDate: `2024-0${i + 1}-28`,
    status,
  }),
)

function mockFetchSuccess(projects: ProjectDto[] = sampleProjects) {
  mockFetchProjects.mockResolvedValue({
    content: projects,
    totalPages: 1,
    totalElements: projects.length,
    number: 0,
    size: 10,
    first: true,
    last: true,
  } as PaginatedResponse<ProjectDto>)
}

/** Count only the data rows (rows inside <tbody>), excluding the header row. */
function dataRowCount(): number {
  return document.querySelectorAll('tbody tr').length
}

// --- Tests ---

describe('ProjectsList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    // FOR-04-13 Req 8.10 gates Create/Edit/Delete behind PROJECTS permissions.
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

  describe('Non-empty rendering (Req 9.2)', () => {
    it('renders name/address/area/startDate/endDate/status with row count == record count', async () => {
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load.
      await waitFor(() => {
        expect(screen.getByText('Проект DRAFT')).toBeInTheDocument()
      })

      // Every record's name renders.
      for (const p of sampleProjects) {
        expect(screen.getByText(p.name)).toBeInTheDocument()
      }

      // Base columns render for a representative row.
      expect(screen.getByText('Адрес 1')).toBeInTheDocument() // address
      expect(screen.getByText('100')).toBeInTheDocument() // area
      expect(screen.getByText('2024-01-01')).toBeInTheDocument() // startDate
      expect(screen.getByText('2024-01-28')).toBeInTheDocument() // endDate

      // Row count equals the number of supplied records.
      expect(dataRowCount()).toBe(sampleProjects.length)
    })
  })

  describe('Localized status badge per status (Req 9.3)', () => {
    it('renders each of the five statuses with its localized badge label', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Проект DRAFT')).toBeInTheDocument()
      })

      // Each status renders exactly its localized badge label.
      expect(screen.getByText('Черновик')).toBeInTheDocument() // DRAFT
      expect(screen.getByText('Активный')).toBeInTheDocument() // ACTIVE
      expect(screen.getByText('Приостановлен')).toBeInTheDocument() // ON_HOLD
      expect(screen.getByText('Завершён')).toBeInTheDocument() // COMPLETED
      expect(screen.getByText('Отменён')).toBeInTheDocument() // CANCELLED
    })
  })

  describe('Permission-gated controls (Req 9.4)', () => {
    it('ADMIN sees the create button and per-row edit/delete actions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Проект DRAFT')).toBeInTheDocument()
      })

      // Create button present and wired.
      const createButton = screen.getByText('Новый проект')
      expect(createButton).toBeInTheDocument()
      fireEvent.click(createButton)
      expect(mockOnCreateProject).toHaveBeenCalledTimes(1)

      // Every row gets edit + delete actions.
      expect(screen.getAllByLabelText('Редактировать')).toHaveLength(
        sampleProjects.length,
      )
      expect(screen.getAllByLabelText('Удалить')).toHaveLength(
        sampleProjects.length,
      )
    })

    it('MANAGER (CREATE/READ/UPDATE, no DELETE) shows create + edit but hides delete', async () => {
      useAuthStore.setState({
        user: {
          id: 2,
          name: 'Manager',
          email: 'manager@example.com',
          roleCode: 'MANAGER',
          permissions: [
            { resource: 'PROJECTS', operations: ['CREATE', 'READ', 'UPDATE'] },
          ],
        },
      })
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Проект DRAFT')).toBeInTheDocument()
      })

      // Create + edit present; delete hidden.
      expect(screen.getByText('Новый проект')).toBeInTheDocument()
      expect(screen.getAllByLabelText('Редактировать')).toHaveLength(
        sampleProjects.length,
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
          permissions: [{ resource: 'PROJECTS', operations: ['READ'] }],
        },
      })
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Проект DRAFT')).toBeInTheDocument()
      })

      expect(screen.queryByText('Новый проект')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Редактировать')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Удалить')).not.toBeInTheDocument()
    })
  })

  describe('Empty state (Req 9.5)', () => {
    it('shows the empty state with zero data rows when the result is empty', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Проекты не найдены')).toBeInTheDocument()
      })

      // No data rows are rendered.
      expect(dataRowCount()).toBe(0)
    })
  })
})
