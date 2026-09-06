import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createWorkPrice, updateWorkPrice, deleteWorkPrice } from './work-prices-api'
import { workPriceKeys } from './query-hooks'
import type { WorkPriceCreateRequest, WorkPriceUpdateRequest } from '../types'

export function useCreateWorkPrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: WorkPriceCreateRequest) => createWorkPrice(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workPriceKeys.lists() })
    },
  })
}

export function useUpdateWorkPrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: WorkPriceUpdateRequest }) =>
      updateWorkPrice(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: workPriceKeys.lists() })
      queryClient.invalidateQueries({ queryKey: workPriceKeys.detail(variables.id) })
    },
  })
}

export function useDeleteWorkPrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteWorkPrice(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: workPriceKeys.lists() })
    },
  })
}
