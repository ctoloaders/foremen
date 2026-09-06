import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { DeliveryStatusDto } from '../types'

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
          'deliveryStatuses.actions.create': 'Utwórz status dostawy',
          'deliveryStatuses.table.orderNo': 'Kolejność',
          'deliveryStatuses.table.code': 'Kod',
          'deliveryStatuses.table.name': 'Nazwa',
          'deliveryStatuses.table.active': 'Aktywny',
          'deliveryStatuses.badge.active': 'Aktywny',
          'deliveryStatuses.badge.inactive': 'Nieaktywny',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono statusów dostaw',
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
import { DeliveryStatusesList } from '../components/DeliveryStatusesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateStatus = vi.fn()
const mockOnEditStatus = vi.fn()
const mockOnDeleteStatus = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <DeliveryStatusesList
        onCreateStatus={mockOnCreateStatus}
        onEditStatus={mockOnEditStatus}
        onDeleteStatus={mockOnDeleteStatus}
      />
    </QueryClientProvider>,
  )
}

const sampleStatuses: DeliveryStatusDto[] = [
  { id: 1, code: 'new', orderNo: 1, name: 'Nowe', active: true },
  { id: 2, code: 'ordered', orderNo: 2, name: 'Zamówione', active: true },
  { id: 3, code: 'cancelled', orderNo: 4, name: 'Anulowane', active: false },
]

function mockFetchSuccess(statuses: DeliveryStatusDto[] = sampleStatuses) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: statuses,
        totalPages: 1,
        totalElements: statuses.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('DeliveryStatusesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind DELIVERY_STATUSES
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
        expect(screen.getByText('new')).toBeInTheDocument()
      })

      expect(screen.getByText('ordered')).toBeInTheDocument()
      expect(screen.getByText('cancelled')).toBeInTheDocument()

      // orderNo column renders the numeric ordering
      expect(screen.getByText('1')).toBeInTheDocument()
      expect(screen.getByText('2')).toBeInTheDocument()
      expect(screen.getByText('4')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Nowe')).toBeInTheDocument()
      expect(screen.getByText('Zamówione')).toBeInTheDocument()
      expect(screen.getByText('Anulowane')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywny').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywny')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz status dostawy')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateStatus).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('new')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleStatuses.length)
      expect(deleteButtons.length).toBe(sampleStatuses.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no statuses', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
