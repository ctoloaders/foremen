import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { OfferPackageDto } from '../types'

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
          'offerPackages.actions.create': 'Utwórz pakiet oferty',
          'offerPackages.table.orderNo': 'Kolejność',
          'offerPackages.table.code': 'Kod',
          'offerPackages.table.name': 'Nazwa',
          'offerPackages.table.active': 'Aktywny',
          'offerPackages.badge.active': 'Aktywny',
          'offerPackages.badge.inactive': 'Nieaktywny',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono pakietów ofert',
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
import { OfferPackagesList } from '../components/OfferPackagesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreatePackage = vi.fn()
const mockOnEditPackage = vi.fn()
const mockOnDeletePackage = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <OfferPackagesList
        onCreatePackage={mockOnCreatePackage}
        onEditPackage={mockOnEditPackage}
        onDeletePackage={mockOnDeletePackage}
      />
    </QueryClientProvider>,
  )
}

const samplePackages: OfferPackageDto[] = [
  { id: 1, code: 'budget', orderNo: 1, name: 'Budżet (START)', active: true },
  { id: 2, code: 'norm', orderNo: 2, name: 'Norma (COMFORT)', active: true },
  { id: 3, code: 'lux', orderNo: 3, name: 'Lux (PRESTIGE)', active: false },
]

function mockFetchSuccess(packages: OfferPackageDto[] = samplePackages) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: packages,
        totalPages: 1,
        totalElements: packages.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('OfferPackagesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind OFFER_PACKAGES
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
        expect(screen.getByText('budget')).toBeInTheDocument()
      })

      expect(screen.getByText('norm')).toBeInTheDocument()
      expect(screen.getByText('lux')).toBeInTheDocument()

      // orderNo column renders the numeric ordering
      expect(screen.getByText('1')).toBeInTheDocument()
      expect(screen.getByText('2')).toBeInTheDocument()
      expect(screen.getByText('3')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Budżet (START)')).toBeInTheDocument()
      expect(screen.getByText('Norma (COMFORT)')).toBeInTheDocument()
      expect(screen.getByText('Lux (PRESTIGE)')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywny').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywny')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz pakiet oferty')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreatePackage).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('budget')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(samplePackages.length)
      expect(deleteButtons.length).toBe(samplePackages.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no packages', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
