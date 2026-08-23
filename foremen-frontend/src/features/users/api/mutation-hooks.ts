import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createUser, updateUser, deactivateUser } from './users-api'
import { userKeys } from './query-hooks'
import type { ApiError } from './users-api'
import type { UserCreateRequest, UserUpdateRequest } from '../types'

export function useCreateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: UserCreateRequest) => createUser(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: userKeys.lists() })
    },
  })
}

export function useUpdateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UserUpdateRequest }) => updateUser(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: userKeys.lists() })
      queryClient.invalidateQueries({ queryKey: userKeys.detail(variables.id) })
    },
  })
}

export function useDeactivateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deactivateUser(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: userKeys.lists() })
    },
  })
}

/**
 * Helper to check if an error is a 409 conflict (email already exists).
 */
export function isEmailConflictError(error: unknown): boolean {
  return (error as ApiError)?.status === 409
}
