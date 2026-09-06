import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { CurrencyDto } from '../types'

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
          'currencies.actions.create': 'Utwórz walutę',
          'currencies.table.code': 'Kod',
          'currencies.table.symbol': 'Symbol',
          'currencies.table.name': 'Nazwa',
          'currencies.table.active': 'Aktywna',
          'currencies.badge.active': 'Aktywna',
          'currencies.badge.inactive': 'Nieaktywna',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono walut',
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
import { CurrenciesList } from '../components/CurrenciesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateCurrency = vi.fn()
const mockOnEditCurrency = vi.fn()
const mockOnDeleteCurrency = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <CurrenciesList
        onCreateCurrency={mockOnCreateCurrency}
        onEditCurrency={mockOnEditCurrency}
        onDeleteCurrency={mockOnDeleteCurrency}
      />
    </QueryClientProvider>,
  )
}

const sampleCurrencies: CurrencyDto[] = [
  { id: 1, code: 'PLN', symbol: 'zł', name: 'Złoty', active: true },
  { id: 2, code: 'EUR', symbol: '€', name: 'Euro', active: true },
  { id: 3, code: 'USD', symbol: '$', name: 'Dolar amerykański', active: false },
]

function mockFetchSuccess(currencies: CurrencyDto[] = sampleCurrencies) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: currencies,
        totalPages: 1,
        totalElements: currencies.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('CurrenciesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind CURRENCIES
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
    it('renders table rows with code, symbol, resolved name, and active badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load and render
      await waitFor(() => {
        expect(screen.getByText('PLN')).toBeInTheDocument()
      })

      expect(screen.getByText('EUR')).toBeInTheDocument()
      expect(screen.getByText('USD')).toBeInTheDocument()

      // Symbol column
      expect(screen.getByText('zł')).toBeInTheDocument()
      expect(screen.getByText('€')).toBeInTheDocument()
      expect(screen.getByText('$')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Złoty')).toBeInTheDocument()
      expect(screen.getByText('Euro')).toBeInTheDocument()
      expect(screen.getByText('Dolar amerykański')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywna').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywna')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz walutę')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateCurrency).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('PLN')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleCurrencies.length)
      expect(deleteButtons.length).toBe(sampleCurrencies.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no currencies', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
