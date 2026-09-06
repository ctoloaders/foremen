import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWorkCategory,
  updateWorkCategory,
  deleteWorkCategory,
} from './work-categories-api'
import { workCategoryKeys } from './query-hooks'
import type {
  WorkCategoryCreateRequest,
  WorkCategoryUpdateRequest,
} from '../types'

export function useCreateWorkCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: WorkCategoryCreateRequest) => createWorkCategory(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workCategoryKeys.lists() })
    },
  })
}

export function useUpdateWorkCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: WorkCategoryUpdateRequest }) =>
      updateWorkCategory(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: workCategoryKeys.lists() })
      queryClient.invalidateQueries({ queryKey: workCategoryKeys.detail(variables.id) })
    },
  })
}

export function useDeleteWorkCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteWorkCategory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workCategoryKeys.lists() })
    },
  })
}
