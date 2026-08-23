import { useQuery, useQueries } from '@tanstack/react-query'
import {
  fetchRoles,
  fetchRole,
  fetchResources,
  fetchOperations,
  fetchRolePermissions,
} from './roles-api'

export const roleKeys = {
  all: ['roles'] as const,
  lists: () => [...roleKeys.all, 'list'] as const,
  list: (params: { page: number; size: number; query: string }) =>
    [...roleKeys.lists(), params] as const,
  details: () => [...roleKeys.all, 'detail'] as const,
  detail: (id: number) => [...roleKeys.details(), id] as const,
  permissions: (id: number) => [...roleKeys.all, 'permissions', id] as const,
  resources: ['resources'] as const,
  operations: ['operations'] as const,
}

export function useRoles(params: { page: number; size: number; query: string }) {
  return useQuery({
    queryKey: roleKeys.list(params),
    queryFn: () => fetchRoles(params),
    staleTime: 30_000,
    retry: 2,
  })
}

export function useRole(id: number | null) {
  return useQuery({
    queryKey: roleKeys.detail(id!),
    queryFn: () => fetchRole(id!),
    enabled: id != null,
    staleTime: 60_000,
    retry: 2,
  })
}

export function useResources() {
  return useQuery({
    queryKey: roleKeys.resources,
    queryFn: () => fetchResources({ page: 0, size: 1000 }),
    staleTime: 60_000,
    retry: 2,
  })
}

export function useOperations() {
  return useQuery({
    queryKey: roleKeys.operations,
    queryFn: () => fetchOperations({ page: 0, size: 100 }),
    staleTime: 60_000,
    retry: 2,
  })
}

export function useRolePermissions(roleId: number | null) {
  return useQuery({
    queryKey: roleKeys.permissions(roleId!),
    queryFn: () => fetchRolePermissions(roleId!),
    enabled: roleId != null,
    staleTime: 30_000,
    retry: 2,
  })
}

export function useMultipleRolePermissions(roleIds: number[]) {
  return useQueries({
    queries: roleIds.map((id) => ({
      queryKey: roleKeys.permissions(id),
      queryFn: () => fetchRolePermissions(id),
      staleTime: 30_000,
      retry: 2,
    })),
  })
}
