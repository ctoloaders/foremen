import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createMaterialCategory,
  updateMaterialCategory,
  deleteMaterialCategory,
} from './material-categories-api'
import { materialCategoryKeys } from './query-hooks'
import type {
  MaterialCategoryCreateRequest,
  MaterialCategoryUpdateRequest,
} from '../types'

export function useCreateMaterialCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: MaterialCategoryCreateRequest) => createMaterialCategory(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: materialCategoryKeys.lists() })
    },
  })
}

export function useUpdateMaterialCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: MaterialCategoryUpdateRequest }) =>
      updateMaterialCategory(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: materialCategoryKeys.lists() })
      queryClient.invalidateQueries({ queryKey: materialCategoryKeys.detail(variables.id) })
    },
  })
}

export function useDeleteMaterialCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteMaterialCategory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: materialCategoryKeys.lists() })
    },
  })
}
