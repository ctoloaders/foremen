import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createDeliveryCategory,
  updateDeliveryCategory,
  deleteDeliveryCategory,
} from './delivery-categories-api'
import { deliveryCategoryKeys } from './query-hooks'
import type {
  DeliveryCategoryCreateRequest,
  DeliveryCategoryUpdateRequest,
} from '../types'

export function useCreateDeliveryCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: DeliveryCategoryCreateRequest) => createDeliveryCategory(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deliveryCategoryKeys.lists() })
    },
  })
}

export function useUpdateDeliveryCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: DeliveryCategoryUpdateRequest }) =>
      updateDeliveryCategory(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: deliveryCategoryKeys.lists() })
      queryClient.invalidateQueries({ queryKey: deliveryCategoryKeys.detail(variables.id) })
    },
  })
}

export function useDeleteDeliveryCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteDeliveryCategory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deliveryCategoryKeys.lists() })
    },
  })
}
