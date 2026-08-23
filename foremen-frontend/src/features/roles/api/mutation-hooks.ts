import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createRole, updateRole, deleteRole, batchUpdatePermissions } from './roles-api'
import { roleKeys } from './query-hooks'
import type { RoleCreateRequest, RoleUpdateRequest, BatchRolePermissionRequest } from '../types'

export function useCreateRole() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: RoleCreateRequest) => createRole(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roleKeys.lists() })
    },
  })
}

export function useUpdateRole() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: RoleUpdateRequest }) => updateRole(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: roleKeys.lists() })
      queryClient.invalidateQueries({ queryKey: roleKeys.detail(variables.id) })
    },
  })
}

export function useDeleteRole() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteRole(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: roleKeys.lists() })
    },
  })
}

export function useBatchUpdatePermissions() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: BatchRolePermissionRequest) => batchUpdatePermissions(data),
    onSuccess: (result) => {
      result.results.forEach((r) => {
        queryClient.invalidateQueries({ queryKey: roleKeys.permissions(r.roleId) })
      })
      queryClient.invalidateQueries({ queryKey: roleKeys.lists() })
    },
  })
}
