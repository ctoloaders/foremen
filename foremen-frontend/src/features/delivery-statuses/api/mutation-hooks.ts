import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createDeliveryStatus,
  updateDeliveryStatus,
  deleteDeliveryStatus,
} from './delivery-statuses-api'
import { deliveryStatusKeys } from './query-hooks'
import type {
  DeliveryStatusCreateRequest,
  DeliveryStatusUpdateRequest,
} from '../types'

export function useCreateDeliveryStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: DeliveryStatusCreateRequest) => createDeliveryStatus(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deliveryStatusKeys.lists() })
    },
  })
}

export function useUpdateDeliveryStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: DeliveryStatusUpdateRequest }) =>
      updateDeliveryStatus(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: deliveryStatusKeys.lists() })
      queryClient.invalidateQueries({ queryKey: deliveryStatusKeys.detail(variables.id) })
    },
  })
}

export function useDeleteDeliveryStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteDeliveryStatus(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: deliveryStatusKeys.lists() })
    },
  })
}
