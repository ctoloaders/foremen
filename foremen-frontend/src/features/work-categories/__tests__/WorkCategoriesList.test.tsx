import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { WorkCategoryDto } from '../types'

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
          'workCategories.actions.create': 'Utwórz kategorię prac',
          'workCategories.table.orderNo': 'Kolejność',
          'workCategories.table.code': 'Kod',
          'workCategories.table.name': 'Nazwa',
          'workCategories.table.active': 'Aktywna',
          'workCategories.badge.active': 'Aktywna',
          'workCategories.badge.inactive': 'Nieaktywna',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono kategorii prac',
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
import { WorkCategoriesList } from '../components/WorkCategoriesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateCategory = vi.fn()
const mockOnEditCategory = vi.fn()
const mockOnDeleteCategory = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkCategoriesList
        onCreateCategory={mockOnCreateCategory}
        onEditCategory={mockOnEditCategory}
        onDeleteCategory={mockOnDeleteCategory}
      />
    </QueryClientProvider>,
  )
}

const sampleCategories: WorkCategoryDto[] = [
  { id: 1, code: 'PRELIMINARY', orderNo: 1, name: 'PRACE WSTĘPNE, DEMONTAŻE', active: true },
  { id: 2, code: 'TILING', orderNo: 7, name: 'PRACE GLAZURNICZE', active: true },
  { id: 3, code: 'OTHER', orderNo: 13, name: 'INNE / KOORDYNACJA / NIESTANDARDOWE', active: false },
]

function mockFetchSuccess(categories: WorkCategoryDto[] = sampleCategories) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: categories,
        totalPages: 1,
        totalElements: categories.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('WorkCategoriesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind WORK_CATEGORIES
    // permissions. Seed an ADMIN user (matrix bypass) so the full action set
    // renders.
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

  describe('Desktop table rendering', () => {
    it('renders table rows with orderNo, code, resolved name, and active badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load and render
      await waitFor(() => {
        expect(screen.getByText('PRELIMINARY')).toBeInTheDocument()
      })

      expect(screen.getByText('TILING')).toBeInTheDocument()
      expect(screen.getByText('OTHER')).toBeInTheDocument()

      // orderNo column renders the numeric ordering
      expect(screen.getByText('1')).toBeInTheDocument()
      expect(screen.getByText('7')).toBeInTheDocument()
      expect(screen.getByText('13')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('PRACE WSTĘPNE, DEMONTAŻE')).toBeInTheDocument()
      expect(screen.getByText('PRACE GLAZURNICZE')).toBeInTheDocument()
      expect(screen.getByText('INNE / KOORDYNACJA / NIESTANDARDOWE')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywna').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywna')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz kategorię prac')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateCategory).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('PRELIMINARY')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleCategories.length)
      expect(deleteButtons.length).toBe(sampleCategories.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no categories', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
