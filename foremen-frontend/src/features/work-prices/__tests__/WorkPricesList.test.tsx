import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { WorkPriceDto } from '../types'

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
          'workPrices.actions.create': 'Utwórz cenę',
          'workPrices.table.workItem': 'Pozycja',
          'workPrices.table.currency': 'Waluta',
          'workPrices.table.netPrice': 'Cena netto',
          'workPrices.table.validFrom': 'Obowiązuje od',
          'workPrices.table.validTo': 'Obowiązuje do',
          'workPrices.table.current': 'Aktualna',
          'workPrices.badge.current': 'Aktualna',
          'workPrices.badge.historic': 'Historyczna',
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
import { WorkPricesList } from '../components/WorkPricesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreatePrice = vi.fn()
const mockOnEditPrice = vi.fn()
const mockOnDeletePrice = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkPricesList
        onCreatePrice={mockOnCreatePrice}
        onEditPrice={mockOnEditPrice}
        onDeletePrice={mockOnDeletePrice}
      />
    </QueryClientProvider>,
  )
}

const samplePrices: WorkPriceDto[] = [
  {
    id: 1,
    workItemId: 10,
    workItemName: 'Układanie płytek',
    currencyId: 100,
    currencyCode: 'PLN',
    netPrice: 120.5,
    validFrom: '2024-09-05',
    validTo: null,
    current: true,
  },
  {
    id: 2,
    workItemId: 11,
    workItemName: 'Montaż listwy',
    currencyId: 100,
    currencyCode: 'PLN',
    netPrice: 44.0,
    validFrom: '2024-01-01',
    validTo: '2024-09-04',
    current: false,
  },
]

function mockFetchSuccess(prices: WorkPriceDto[] = samplePrices) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: prices,
        totalPages: 1,
        totalElements: prices.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('WorkPricesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind WORK_PRICES
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
    it('renders rows with workItem, currency, netPrice, validFrom, and current badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('Układanie płytek')).toBeInTheDocument()
      })

      expect(screen.getByText('Montaż listwy')).toBeInTheDocument()

      // Reference columns render the server-resolved display values.
      expect(screen.getAllByText('PLN').length).toBeGreaterThan(0)

      // Prices and validFrom render.
      expect(screen.getByText('120.5')).toBeInTheDocument()
      expect(screen.getByText('2024-09-05')).toBeInTheDocument()

      // Current badge renders localized Current/Historic text.
      expect(screen.getAllByText('Aktualna').length).toBeGreaterThan(0)
      expect(screen.getByText('Historyczna')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz cenę')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreatePrice).toHaveBeenCalledTimes(1)
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
      expect(editButtons.length).toBe(samplePrices.length)
      expect(deleteButtons.length).toBe(samplePrices.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no prices', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
