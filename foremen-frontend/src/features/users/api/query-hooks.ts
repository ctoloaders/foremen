import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { fetchUser, fetchRolesForSelect, fetchRolesPage } from './users-api'

export const userKeys = {
  all: ['users'] as const,
  lists: () => [...userKeys.all, 'list'] as const,
  details: () => [...userKeys.all, 'detail'] as const,
  detail: (id: number) => [...userKeys.details(), id] as const,
  roles: () => [...userKeys.all, 'roles'] as const,
}

export function useUser(id: number | null) {
  return useQuery({
    queryKey: userKeys.detail(id!),
    queryFn: () => fetchUser(id!),
    enabled: id != null,
    staleTime: 30_000,
    retry: 2,
  })
}

export function useRolesForSelect() {
  return useQuery({
    queryKey: userKeys.roles(),
    queryFn: () => fetchRolesForSelect(),
    staleTime: 60_000,
    retry: 2,
  })
}

/**
 * Role codes that are never assignable through the user-form RoleSelect
 * dropdown. These roles are managed elsewhere (ADMIN by the platform, CLIENT
 * by the client-registration flow), so they are excluded from the create/edit
 * user forms. Exclusion is keyed on the stable role `code`, not the display
 * name, so renaming or reordering roles cannot defeat the filter.
 *
 * Requirements: 11.1, 11.2, 11.3
 */
export const EXCLUDED_ROLE_CODES = ['ADMIN', 'CLIENT'] as const

/**
 * Infinite query for roles used by the RoleSelect dropdown.
 *
 * Fetches one page (size=20) at a time and supports server-side search via an
 * RSQL-style `name~ct~{search}` query. Pagination continues until the last
 * Spring Data page is reached.
 *
 * ADMIN and CLIENT are excluded server-side via a `code~notin~ADMIN,CLIENT`
 * filter (combined with the search term via `AND`) so they never enter the
 * options list. This keeps pagination counts correct and search consistent.
 * `RoleSelect` also applies a client-side fallback filter as defense in depth.
 *
 * @param search Optional search term applied as `name~ct~{search}`.
 */
export function useRolesInfinite(search?: string) {
  const excludeClause = `code~notin~${EXCLUDED_ROLE_CODES.join(',')}`
  const query = search ? `name~ct~${search} AND ${excludeClause}` : excludeClause

  return useInfiniteQuery({
    queryKey: [...userKeys.roles(), { search, exclude: EXCLUDED_ROLE_CODES }],
    queryFn: ({ pageParam = 0 }) =>
      fetchRolesPage({
        page: pageParam,
        size: 20,
        query,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    staleTime: 60_000,
  })
}
