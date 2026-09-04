import { renderHook, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { PaginatedResponse, RoleOption } from '../types'

// --- Mock the roles API layer so we can inspect the query params sent by hooks ---

const mockFetchRolesPage =
  vi.fn<
    (params: { page: number; size: number; query?: string }) => Promise<
      PaginatedResponse<RoleOption>
    >
  >()
const mockFetchRolesForSelect = vi.fn<() => Promise<RoleOption[]>>()

vi.mock('../api/users-api', () => ({
  fetchUser: vi.fn(),
  fetchRolesPage: (params: { page: number; size: number; query?: string }) =>
    mockFetchRolesPage(params),
  fetchRolesForSelect: () => mockFetchRolesForSelect(),
}))

import {
  EXCLUDED_ROLE_CODES,
  useRolesInfinite,
  useRolesForSelect,
} from '../api/query-hooks'

// --- Helpers ---

function emptyPage(): PaginatedResponse<RoleOption> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 1,
    number: 0,
    size: 20,
    first: true,
    last: true,
  }
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

// --- Tests ---

describe('roles query hooks — RoleSelect exclusion (Requirement 11.3, 11.5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchRolesPage.mockResolvedValue(emptyPage())
    mockFetchRolesForSelect.mockResolvedValue([])
  })

  it('EXCLUDED_ROLE_CODES is exactly ADMIN and CLIENT', () => {
    expect([...EXCLUDED_ROLE_CODES]).toEqual(['ADMIN', 'CLIENT'])
  })

  describe('useRolesInfinite (user-form RoleSelect consumer)', () => {
    it('excludes ADMIN and CLIENT by code via a server-side filter (11.3)', async () => {
      renderHook(() => useRolesInfinite(), { wrapper: createWrapper() })

      await waitFor(() => {
        expect(mockFetchRolesPage).toHaveBeenCalled()
      })

      const params = mockFetchRolesPage.mock.calls[0]![0]
      // Exclusion keys on the stable role code, not on display name/position.
      expect(params.query).toBe('code~notin~ADMIN,CLIENT')
    })

    it('combines the search term with the exclusion filter (11.3)', async () => {
      renderHook(() => useRolesInfinite('man'), { wrapper: createWrapper() })

      await waitFor(() => {
        expect(mockFetchRolesPage).toHaveBeenCalled()
      })

      const params = mockFetchRolesPage.mock.calls[0]![0]
      expect(params.query).toBe('name~ct~man AND code~notin~ADMIN,CLIENT')
    })
  })

  describe('useRolesForSelect (a different /api/roles consumer)', () => {
    it('does NOT apply the ADMIN/CLIENT exclusion — other consumers are unaffected (11.5)', async () => {
      renderHook(() => useRolesForSelect(), { wrapper: createWrapper() })

      await waitFor(() => {
        expect(mockFetchRolesForSelect).toHaveBeenCalled()
      })

      // This consumer fetches roles through fetchRolesForSelect, which carries
      // no exclusion query at all — so it never applies the RoleSelect-only
      // ADMIN/CLIENT filter. Proving the filter is scoped to useRolesInfinite.
      expect(mockFetchRolesForSelect).toHaveBeenCalledWith()
      expect(mockFetchRolesPage).not.toHaveBeenCalled()
    })
  })
})
