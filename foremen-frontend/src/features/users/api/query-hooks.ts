import { useQuery } from '@tanstack/react-query'
import { fetchUser, fetchRolesForSelect } from './users-api'

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
