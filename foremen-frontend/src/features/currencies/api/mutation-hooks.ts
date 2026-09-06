import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createCurrency,
  updateCurrency,
  deleteCurrency,
} from './currencies-api'
import { currencyKeys } from './query-hooks'
import type {
  CurrencyCreateRequest,
  CurrencyUpdateRequest,
} from '../types'

export function useCreateCurrency() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: CurrencyCreateRequest) => createCurrency(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: currencyKeys.lists() })
    },
  })
}

export function useUpdateCurrency() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: CurrencyUpdateRequest }) =>
      updateCurrency(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: currencyKeys.lists() })
      queryClient.invalidateQueries({ queryKey: currencyKeys.detail(variables.id) })
    },
  })
}

export function useDeleteCurrency() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteCurrency(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: currencyKeys.lists() })
    },
  })
}
