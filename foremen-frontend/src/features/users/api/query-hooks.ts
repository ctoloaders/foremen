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
 * Infinite query for roles used by the RoleSelect dropdown.
 *
 * Fetches one page (size=20) at a time and supports server-side search via an
 * RSQL-style `name~ct~{search}` query. Pagination continues until the last
 * Spring Data page is reached.
 *
 * @param search Optional search term applied as `name~ct~{search}`.
 */
export function useRolesInfinite(search?: string) {
  return useInfiniteQuery({
    queryKey: [...userKeys.roles(), { search }],
    queryFn: ({ pageParam = 0 }) =>
      fetchRolesPage({
        page: pageParam,
        size: 20,
        query: search ? `name~ct~${search}` : undefined,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.last ? undefined : lastPage.number + 1),
    staleTime: 60_000,
  })
}
