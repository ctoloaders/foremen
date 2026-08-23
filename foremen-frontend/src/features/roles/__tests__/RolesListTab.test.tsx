import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UseQueryResult } from '@tanstack/react-query'

import i18n from '@/lib/i18n'
import type { PaginatedResponse, RoleExtendedDto } from '../types'

// --- Mocks ---

const mockUseBreakpoint = vi.fn<() => 'desktop' | 'tablet' | 'mobile'>(() => 'desktop')

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockUseBreakpoint(),
}))

const mockUseRoles = vi.fn<() => Partial<UseQueryResult<PaginatedResponse<RoleExtendedDto>>>>()

vi.mock('../api/query-hooks', () => ({
  useRoles: () => mockUseRoles(),
}))

// Import the component AFTER mocks are set up
import { RolesListTab } from '../components/RolesListTab'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateRole = vi.fn()
const mockOnEditRole = vi.fn()
const mockOnDeleteRole = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <RolesListTab
        onCreateRole={mockOnCreateRole}
        onEditRole={mockOnEditRole}
        onDeleteRole={mockOnDeleteRole}
      />
    </QueryClientProvider>,
  )
}

const sampleRoles: RoleExtendedDto[] = [
  {
    id: 1,
    code: 'ADMIN',
    nameRU: 'Администратор',
    namePL: 'Administrator',
    descriptionRU: 'Полный доступ',
    descriptionPL: 'Pełny dostęp',
    system: true,
  },
  {
    id: 2,
    code: 'MANAGER',
    nameRU: 'Менеджер',
    namePL: 'Menadżer',
    descriptionRU: 'Управление проектами',
    descriptionPL: 'Zarządzanie projektami',
    system: false,
  },
  {
    id: 3,
    code: 'CLIENT',
    nameRU: 'Клиент',
    namePL: 'Klient',
    descriptionRU: null,
    descriptionPL: null,
    system: false,
  },
]

// --- Tests ---

describe('RolesListTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    void i18n.changeLanguage('pl')
  })

  describe('Loading state', () => {
    it('shows skeleton while loading', () => {
      mockUseRoles.mockReturnValue({
        data: undefined,
        isLoading: true,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      // Skeleton has animate-pulse elements for search bar and content
      const pulsingElements = document.querySelectorAll('.animate-pulse')
      expect(pulsingElements.length).toBeGreaterThan(0)
    })
  })

  describe('Desktop table rendering', () => {
    it('renders table rows with role data on desktop', () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockUseRoles.mockReturnValue({
        data: {
          content: sampleRoles,
          totalPages: 1,
          totalElements: 3,
          number: 0,
          size: 10,
        },
        isLoading: false,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      // Role codes should be visible in the table
      expect(screen.getByText('ADMIN')).toBeInTheDocument()
      expect(screen.getByText('MANAGER')).toBeInTheDocument()
      expect(screen.getByText('CLIENT')).toBeInTheDocument()

      // PL locale names should be visible
      expect(screen.getByText('Administrator')).toBeInTheDocument()
      expect(screen.getByText('Menadżer')).toBeInTheDocument()
      expect(screen.getByText('Klient')).toBeInTheDocument()
    })
  })

  describe('System badge', () => {
    it('displays system badge for system roles', () => {
      mockUseRoles.mockReturnValue({
        data: {
          content: sampleRoles,
          totalPages: 1,
          totalElements: 3,
          number: 0,
          size: 10,
        },
        isLoading: false,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      // "Systemowa" appears as both the table header and the badge.
      // The badge uses the Badge component (shadcn secondary variant).
      // Only 1 role is system, so there should be exactly 1 badge element.
      const badges = screen.getAllByText('Systemowa')
      // 2 occurrences: table header + 1 badge for ADMIN
      expect(badges.length).toBe(2)
      // Verify at least one is rendered as a badge (inside a div with the role row)
      const badgeEl = badges.find((el) => el.classList.contains('inline-flex'))
      expect(badgeEl).toBeDefined()
    })
  })

  describe('System role delete protection', () => {
    it('delete button is disabled for system roles', () => {
      mockUseRoles.mockReturnValue({
        data: {
          content: sampleRoles,
          totalPages: 1,
          totalElements: 3,
          number: 0,
          size: 10,
        },
        isLoading: false,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      // Get all delete buttons (aria-label "Usuń")
      const deleteButtons = screen.getAllByLabelText('Usuń')
      // The first role (ADMIN) is system — its delete button should be disabled
      expect(deleteButtons[0]).toBeDisabled()
      // Non-system roles should have enabled delete buttons
      expect(deleteButtons[1]).not.toBeDisabled()
      expect(deleteButtons[2]).not.toBeDisabled()
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no roles match filter', () => {
      mockUseRoles.mockReturnValue({
        data: {
          content: [],
          totalPages: 0,
          totalElements: 0,
          number: 0,
          size: 10,
        },
        isLoading: false,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      expect(screen.getByText('Nie znaleziono ról')).toBeInTheDocument()
    })
  })

  describe('Create Role button', () => {
    it('"Create Role" button is rendered and clickable', () => {
      mockUseRoles.mockReturnValue({
        data: {
          content: sampleRoles,
          totalPages: 1,
          totalElements: 3,
          number: 0,
          size: 10,
        },
        isLoading: false,
        isError: false,
        refetch: vi.fn(),
      })

      renderComponent()

      const createButton = screen.getByText('Utwórz rolę')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateRole).toHaveBeenCalledTimes(1)
    })
  })
})
