import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createVatRate,
  updateVatRate,
  deleteVatRate,
} from './vat-rates-api'
import { vatRateKeys } from './query-hooks'
import type {
  VatRateCreateRequest,
  VatRateUpdateRequest,
} from '../types'

export function useCreateVatRate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: VatRateCreateRequest) => createVatRate(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: vatRateKeys.lists() })
    },
  })
}

export function useUpdateVatRate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: VatRateUpdateRequest }) =>
      updateVatRate(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: vatRateKeys.lists() })
      queryClient.invalidateQueries({ queryKey: vatRateKeys.detail(variables.id) })
    },
  })
}

export function useDeleteVatRate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteVatRate(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: vatRateKeys.lists() })
    },
  })
}
