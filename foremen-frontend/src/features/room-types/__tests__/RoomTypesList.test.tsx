import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth-store'

import type { RoomTypeDto } from '../types'

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
          'roomTypes.actions.create': 'Utwórz typ pomieszczenia',
          'roomTypes.table.code': 'Kod',
          'roomTypes.table.name': 'Nazwa',
          'roomTypes.table.active': 'Aktywny',
          'roomTypes.badge.active': 'Aktywny',
          'roomTypes.badge.inactive': 'Nieaktywny',
          'common.edit': 'Edytuj',
          'common.delete': 'Usuń',
          'dataTable.search': 'Szukaj...',
          'dataTable.empty': 'Nie znaleziono typów pomieszczeń',
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
import { RoomTypesList } from '../components/RoomTypesList'

// --- Test Helpers ---

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const mockOnCreateRoomType = vi.fn()
const mockOnEditRoomType = vi.fn()
const mockOnDeleteRoomType = vi.fn()

function renderComponent() {
  const queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <RoomTypesList
        onCreateRoomType={mockOnCreateRoomType}
        onEditRoomType={mockOnEditRoomType}
        onDeleteRoomType={mockOnDeleteRoomType}
      />
    </QueryClientProvider>,
  )
}

const sampleRoomTypes: RoomTypeDto[] = [
  { id: 1, code: 'kuchnia', name: 'Kuchnia', active: true },
  { id: 2, code: 'salon', name: 'Salon', active: true },
  { id: 3, code: 'lazienka', name: 'Łazienka', active: false },
]

function mockFetchSuccess(roomTypes: RoomTypeDto[] = sampleRoomTypes) {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(
      JSON.stringify({
        content: roomTypes,
        totalPages: 1,
        totalElements: roomTypes.length,
        number: 0,
        size: 10,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ),
  )
}

// --- Tests ---

describe('RoomTypesList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseBreakpoint.mockReturnValue('desktop')
    localStorage.setItem('foremen-locale', 'pl')
    // FOR-03-07 gates the Create/Edit/Delete actions behind ROOM_TYPES
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
        expect(screen.getByText('kuchnia')).toBeInTheDocument()
      })

      expect(screen.getByText('salon')).toBeInTheDocument()
      expect(screen.getByText('lazienka')).toBeInTheDocument()

      // Name column shows locale-resolved names
      expect(screen.getByText('Kuchnia')).toBeInTheDocument()
      expect(screen.getByText('Salon')).toBeInTheDocument()
      expect(screen.getByText('Łazienka')).toBeInTheDocument()

      // Active badge renders localized Active/Inactive text
      expect(screen.getAllByText('Aktywny').length).toBeGreaterThan(0)
      expect(screen.getByText('Nieaktywny')).toBeInTheDocument()
    })
  })

  describe('Permission-gated Create button', () => {
    it('"Create" button is rendered and clickable with CREATE permission', async () => {
      mockFetchSuccess()

      renderComponent()

      const createButton = screen.getByText('Utwórz typ pomieszczenia')
      expect(createButton).toBeInTheDocument()

      fireEvent.click(createButton)
      expect(mockOnCreateRoomType).toHaveBeenCalledTimes(1)
    })
  })

  describe('Row actions', () => {
    it('renders edit and delete buttons for every row with CRUD permissions', async () => {
      mockFetchSuccess()

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('kuchnia')).toBeInTheDocument()
      })

      // No system-delete guard: every row gets both edit and delete.
      const editButtons = screen.getAllByLabelText('Edytuj')
      const deleteButtons = screen.getAllByLabelText('Usuń')
      expect(editButtons.length).toBe(sampleRoomTypes.length)
      expect(deleteButtons.length).toBe(sampleRoomTypes.length)
    })
  })

  describe('Empty state', () => {
    it('shows empty state message when no room types', async () => {
      mockFetchSuccess([])

      renderComponent()

      await waitFor(() => {
        expect(screen.getByText('dataTable.empty.filtered')).toBeInTheDocument()
      })
    })
  })
})
