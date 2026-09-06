import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { WorkItemDto } from '../types'

// --- Mocks ---

// Keep the real react-i18next module and only override useTranslation so `t`
// returns stable labels for assertions.
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => {
        const translations: Record<string, string> = {
          'workCatalog.actions.create': 'Utwórz pozycję',
          'workCatalog.table.name': 'Nazwa',
          'workCatalog.table.workCategory': 'Kategoria',
          'workCatalog.table.unit': 'Jednostka',
          'workCatalog.table.active': 'Aktywny',
          'workCatalog.badge.active': 'Aktywny',
          'workCatalog.badge.inactive': 'Nieaktywny',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono pozycji',
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
import { WorkCatalogList } from '../components/WorkCatalogList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateItem = vi.fn()
const mockOnEditItem = vi.fn()
const mockOnDeleteItem = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkCatalogList
        onCreateItem={mockOnCreateItem}
        onEditItem={mockOnEditItem}
        onDeleteItem={mockOnDeleteItem}
      />
    </QueryClientProvider>,
  )
}

const sampleItems: WorkItemDto[] = [
  {
    id: 1,
    workCategoryId: 10,
    workCategoryName: 'PRACE GLAZURNICZE',
    unitId: 100,
    unitName: 'Metr kwadratowy',
    name: 'Układanie płytek',
    active: true,
  },
  {
    id: 2,
    workCategoryId: 11,
    workCategoryName: 'POSADZKI',
    unitId: 101,
    unitName: 'Metr bieżący',
    name: 'Montaż listwy',
    active: false,
  },
]

function mockFetchSuccess(items: WorkItemDto[] = sampleItems) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: items,
        totalPages: 1,
        totalElements: items.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('WorkCatalogList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind WORK_CATALOG
    // permissions. Seed an ADMIN user (matrix bypass) so the full action set renders.
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
    it('renders rows with name, referenced category/unit names, and active badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Układanie płytek')).toBeInTheDocument()
      })

      expect(screen.getByText('Montaż listwy')).toBeInTheDocument()

      // Reference columns render the server-resolved localized display names.
      expect(screen.getByText('PRACE GLAZURNICZE')).toBeInTheDocument()
      expect(screen.getByText('POSADZKI')).toBeInTheDocument()
      expect(screen.getByText('Metr kwadratowy')).toBeInTheDocument()
      expect(screen.getByText('Metr bieżący')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text.
      expect(screen.getAllByText('Aktywny').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywny')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz pozycję')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateItem).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Układanie płytek')).toBeInTheDocument()
      })

      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleItems.length)
      expect(deleteButtons.length).toBe(sampleItems.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no items', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
