import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { VatRateDto } from '../types'

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
          'vatRates.actions.create': 'Utwórz stawkę VAT',
          'vatRates.table.code': 'Kod',
          'vatRates.table.rate': 'Stawka',
          'vatRates.table.name': 'Nazwa',
          'vatRates.table.isDefault': 'Domyślna',
          'vatRates.table.active': 'Aktywna',
          'vatRates.badge.active': 'Aktywna',
          'vatRates.badge.inactive': 'Nieaktywna',
          'vatRates.badge.default': 'Domyślna',
          'vatRates.badge.notDefault': 'Nie',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono stawek VAT',
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
import { VatRatesList } from '../components/VatRatesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateVatRate = vi.fn()
const mockOnEditVatRate = vi.fn()
const mockOnDeleteVatRate = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <VatRatesList
        onCreateVatRate={mockOnCreateVatRate}
        onEditVatRate={mockOnEditVatRate}
        onDeleteVatRate={mockOnDeleteVatRate}
      />
    </QueryClientProvider>,
  )
}

const sampleVatRates: VatRateDto[] = [
  { id: 1, code: '23', rate: 23, name: '23%', isDefault: true, active: true },
  { id: 2, code: '8', rate: 8, name: '8%', isDefault: false, active: true },
  { id: 3, code: '0', rate: 0, name: '0%', isDefault: false, active: false },
]

function mockFetchSuccess(vatRates: VatRateDto[] = sampleVatRates) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: vatRates,
        totalPages: 1,
        totalElements: vatRates.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('VatRatesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind VAT_RATES
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
    it('renders table rows with code, rate, resolved name, isDefault badge, and active badge', async () => {
      mockUseBreakpoint.mockReturnValue('desktop')
      mockFetchSuccess()

      renderComponent()

      // Wait for data to load and render. Name column shows locale-resolved
      // names, which are unique per row (unlike the code/rate cells that both
      // render the numeric value, e.g. "23").
      await waitFor(() => {
        expect(screen.getByText('23%')).toBeInTheDocument()
      })

      expect(screen.getByText('8%')).toBeInTheDocument()
      expect(screen.getByText('0%')).toBeInTheDocument()

      // The code and rate cells both render the bare number (e.g. "23"),
      // so there are two matches per row value.
      expect(screen.getAllByText('23').length).toBeGreaterThanOrEqual(2)
      expect(screen.getAllByText('8').length).toBeGreaterThanOrEqual(2)

      // isDefault badge renders localized Default / Not-default text.
      // "Domyślna" is used for both the column header and the default badge,
      // so there are at least two matches (header + the one default row).
      expect(screen.getAllByText('Domyślna').length).toBeGreaterThanOrEqual(2)
      expect(screen.getAllByText('Nie').length).toBeGreaterThan(0)

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywna').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywna')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz stawkę VAT')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateVatRate).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('23%')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleVatRates.length)
      expect(deleteButtons.length).toBe(sampleVatRates.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no VAT rates', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
