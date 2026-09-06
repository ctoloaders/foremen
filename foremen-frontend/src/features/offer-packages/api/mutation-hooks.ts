import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createOfferPackage,
  updateOfferPackage,
  deleteOfferPackage,
} from './offer-packages-api'
import { offerPackageKeys } from './query-hooks'
import type {
  OfferPackageCreateRequest,
  OfferPackageUpdateRequest,
} from '../types'

export function useCreateOfferPackage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: OfferPackageCreateRequest) => createOfferPackage(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: offerPackageKeys.lists() })
    },
  })
}

export function useUpdateOfferPackage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: OfferPackageUpdateRequest }) =>
      updateOfferPackage(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: offerPackageKeys.lists() })
      queryClient.invalidateQueries({ queryKey: offerPackageKeys.detail(variables.id) })
    },
  })
}

export function useDeleteOfferPackage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteOfferPackage(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: offerPackageKeys.lists() })
    },
  })
}
