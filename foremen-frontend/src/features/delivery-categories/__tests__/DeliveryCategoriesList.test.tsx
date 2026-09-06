import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { DeliveryCategoryDto } from '../types'

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
          'deliveryCategories.actions.create': 'Utwórz kategorię dostawy',
          'deliveryCategories.table.code': 'Kod',
          'deliveryCategories.table.name': 'Nazwa',
          'deliveryCategories.table.active': 'Aktywna',
          'deliveryCategories.badge.active': 'Aktywna',
          'deliveryCategories.badge.inactive': 'Nieaktywna',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono kategorii dostaw',
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
import { DeliveryCategoriesList } from '../components/DeliveryCategoriesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateDeliveryCategory = vi.fn()
const mockOnEditDeliveryCategory = vi.fn()
const mockOnDeleteDeliveryCategory = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <DeliveryCategoriesList
        onCreateDeliveryCategory={mockOnCreateDeliveryCategory}
        onEditDeliveryCategory={mockOnEditDeliveryCategory}
        onDeleteDeliveryCategory={mockOnDeleteDeliveryCategory}
      />
    </QueryClientProvider>,
  )
}

const sampleDeliveryCategories: DeliveryCategoryDto[] = [
  { id: 1, code: 'tiles', name: 'Płytki', active: true },
  { id: 2, code: 'paints', name: 'Farby', active: true },
  { id: 3, code: 'doors', name: 'Drzwi', active: false },
]

function mockFetchSuccess(deliveryCategories: DeliveryCategoryDto[] = sampleDeliveryCategories) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: deliveryCategories,
        totalPages: 1,
        totalElements: deliveryCategories.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('DeliveryCategoriesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind DELIVERY_CATEGORIES
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
    it('renders table rows with code, resolved name, and active badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load and render
      await waitFor(() => {
        expect(screen.getByText('tiles')).toBeInTheDocument()
      })

      expect(screen.getByText('paints')).toBeInTheDocument()
      expect(screen.getByText('doors')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Płytki')).toBeInTheDocument()
      expect(screen.getByText('Farby')).toBeInTheDocument()
      expect(screen.getByText('Drzwi')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywna').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywna')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz kategorię dostawy')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateDeliveryCategory).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('tiles')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleDeliveryCategories.length)
      expect(deleteButtons.length).toBe(sampleDeliveryCategories.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no delivery categories', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
