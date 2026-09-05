import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { RoleDto } from '../types'

// --- Mocks ---

// Keep the real react-i18next module (i18n.ts wires initReactI18next, pulled in
// transitively via usePermission → Auth_Store → api-client → i18n) and only
// override useTranslation so `t` returns stable labels for assertions.
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => {
        const translations: Record<string, string> = {
          'roles.actions.create': 'Utwórz rolę',
          'roles.table.code': 'Kod',
          'roles.table.name': 'Nazwa',
          'roles.table.description': 'Opis',
          'roles.table.system': 'Systemowa',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono ról',
          'dataTable.filter.open': 'dataTable.filter.open',
        }
        return translations[key] ?? key
      },
      i18n: { language: 'pl', changeLanguage: vi.fn() },
    }),
  }
})

const mockUseBreakpoint = vi.fn<() => 'desktop' | 'tablet' | 'mobile'>(() => 'desktop')

vi.mock('@/hooks/useBreakpoint', () => ({
  useBreakpoint: () => mockUseBreakpoint(),
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

const sampleRoles: RoleDto[] = [
  {
    id: 1,
    code: 'ADMIN',
    name: 'Administrator',
    description: 'Pełny dostęp',
    system: true,
  },
  {
    id: 2,
    code: 'MANAGER',
    name: 'Menadżer',
    description: 'Zarządzanie projektami',
    system: false,
  },
  {
    id: 3,
    code: 'CLIENT',
    name: 'Klient',
    description: null,
    system: false,
  },
]

function mockFetchSuccess(roles: RoleDto[] = sampleRoles) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({
      content: roles,
      totalPages: 1,
      totalElements: roles.length,
      number: 0,
      size: 10,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
  )
}

// --- Tests ---

describe('RolesListTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind ROLES permissions.
    // Seed an ADMIN user (matrix bypass) so the full action set renders.
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

  describe('Loading state', () => {
    it('shows skeleton while loading', () => {
      // Return a never-resolving promise to simulate loading
      vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))

      renderComponent()

      // Skeleton has animate-pulse elements for content
      const pulsingElements = document.querySelectorAll('.animate-pulse')
      expect(pulsingElements.length).toBeGreaterThan(0)
    })
  })

  describe('Desktop table rendering', () => {
    it('renders table rows with role data on desktop', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load and render
      await waitFor(() => {
        expect(screen.getByText('ADMIN')).toBeInTheDocument()
      })

      expect(screen.getByText('MANAGER')).toBeInTheDocument()
      expect(screen.getByText('CLIENT')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Administrator')).toBeInTheDocument()
      expect(screen.getByText('Menadżer')).toBeInTheDocument()
      expect(screen.getByText('Klient')).toBeInTheDocument()
    })
  })

  describe('System badge', () => {
    it('displays system badge for system roles', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('ADMIN')).toBeInTheDocument()
      })

      // The system column renders ✓ for system roles
      const checkmarks = screen.getAllByText('✓')
      expect(checkmarks.length).toBe(1) // only ADMIN is system
    })
  })

  describe('System role delete protection', () => {
    it('delete button is not shown for system roles', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('ADMIN')).toBeInTheDocument()
      })

      // The component conditionally renders delete button only for non-system roles
      const deleteButtons = screen.getAllByLabelText('Usuń')
      // Only non-system roles (MANAGER, CLIENT) have delete buttons
      expect(deleteButtons.length).toBe(2)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no roles', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })

  describe('Create Role button', () => {
    it('"Create Role" button is rendered and clickable', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz rolę')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateRole).toHaveBeenCalledTimes(1)
    })
  })
})
